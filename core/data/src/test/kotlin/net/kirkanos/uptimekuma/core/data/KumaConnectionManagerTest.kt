package net.kirkanos.uptimekuma.core.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.kirkanos.uptimekuma.core.data.connection.ConnectionTimings
import net.kirkanos.uptimekuma.core.data.connection.KumaApiFactory
import net.kirkanos.uptimekuma.core.data.connection.KumaConnectionManager
import net.kirkanos.uptimekuma.core.data.connection.KumaConnectionManager.Companion.TOKEN_KEY
import net.kirkanos.uptimekuma.core.data.connection.NetworkMonitor
import net.kirkanos.uptimekuma.core.kumaclient.KumaClientState
import net.kirkanos.uptimekuma.core.kumaclient.KumaEvent
import net.kirkanos.uptimekuma.core.kumaclient.LoginResponse
import net.kirkanos.uptimekuma.core.kumaclient.dto.InfoDto
import net.kirkanos.uptimekuma.core.model.ConnectionState
import net.kirkanos.uptimekuma.core.model.KumaError
import net.kirkanos.uptimekuma.core.model.KumaException
import net.kirkanos.uptimekuma.core.model.LoginResult
import net.kirkanos.uptimekuma.core.model.ServerConfig
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@OptIn(ExperimentalCoroutinesApi::class)
class KumaConnectionManagerTest {
    private val cache = FakeKumaCache()
    private val secrets = FakeSecretStore()
    private val apis = mutableListOf<FakeKumaApi>()
    private val events = mutableListOf<String>()
    private var configureApi: (FakeKumaApi) -> Unit = {}
    private val factory = KumaApiFactory { url -> FakeKumaApi(url).also(configureApi).also { apis += it } }
    private val timings =
        ConnectionTimings(backgroundGraceMillis = 30_000, readyTimeoutMillis = 5_000, infoTimeoutMillis = 1_000)
    private val stored = ServerConfig(baseUrl = "https://kuma.example", username = "admin")
    private lateinit var configStore: FakeConfigStore
    private val scopes = mutableListOf<CoroutineScope>()
    private val network = MutableSharedFlow<Long?>(replay = 1)
    private var networkSubscriptions = 0
    private val networkMonitor =
        NetworkMonitor { network.onStart { networkSubscriptions++ }.onCompletion { networkSubscriptions-- } }

    @AfterEach
    fun cancelScopes() = scopes.forEach { it.cancel() }

    private fun TestScope.manager(config: ServerConfig? = null): KumaConnectionManager {
        configStore = FakeConfigStore(config)
        return KumaConnectionManager(
            apiFactory = factory,
            cache = cache,
            configStore = configStore,
            secretStore = secrets,
            logoutHooks = setOf(LogoutHook { events += "hook(token=${secrets.values[TOKEN_KEY]})" }),
            scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)).also { scopes += it },
            timings = timings,
            networkMonitor = networkMonitor,
        )
    }

    private fun connected(state: ConnectionState) = (state as? ConnectionState.Connected)?.serverInfo?.version

    @Test
    fun `interactive login normalizes url, persists config and token and syncs`() =
        runTest {
            val m = manager()
            m.setForeground(true)
            advanceUntilIdle()
            assertEquals(ConnectionState.NotConfigured, m.state.value)

            assertEquals(LoginResult.Success, m.login("kuma.example/", " admin ", "pw", null))
            advanceUntilIdle()

            assertEquals("https://kuma.example", apis.single().baseUrl)
            assertEquals(stored, configStore.current())
            assertEquals("jwt-1", secrets.values[TOKEN_KEY])
            assertEquals("2.5.8", connected(m.state.value))
            assertEquals("2.5.8", m.serverInfo.value?.version)
            assertEquals(13, cache.monitors.size)
            assertEquals(2, cache.tags.size, "getTags after login")
            assertFalse(apis.single().closed, "kept for immediate data")
            assertTrue(apis.single().calls.contains("login:admin"))
        }

    @Test
    fun `two factor login keeps the socket for the totp step`() =
        runTest {
            configureApi = { api ->
                api.loginResponse =
                    { _, _, totp ->
                        if (totp ==
                            "123456"
                        ) {
                            LoginResponse.Success("jwt-1")
                        } else {
                            LoginResponse.TwoFactorRequired
                        }
                    }
            }
            val m = manager()
            m.setForeground(true)
            assertEquals(LoginResult.TwoFactorRequired, m.login("https://kuma.example", "admin", "pw", null))
            assertNull(secrets.values[TOKEN_KEY])
            assertEquals(LoginResult.Success, m.login("https://kuma.example", "admin", "pw", "123456"))
            assertEquals(1, apis.size, "same socket reused")
            assertEquals("jwt-1", secrets.values[TOKEN_KEY])
        }

    @Test
    fun `setup required and bad credentials fail without persisting`() =
        runTest {
            configureApi = { it.needSetup = true }
            val m = manager()
            assertEquals(
                LoginResult.Failure(KumaError(KumaError.Kind.SETUP_REQUIRED)),
                m.login("https://kuma.example", "a", "b", null),
            )
            assertTrue(apis.last().closed)

            configureApi = {
                it.loginResponse =
                    {
                        _,
                        _,
                        _,
                        ->
                        throw KumaException(KumaError(KumaError.Kind.INVALID_CREDENTIALS, "authIncorrectCreds"))
                    }
            }
            val failure = m.login("https://kuma.example", "a", "b", null) as LoginResult.Failure
            assertEquals(KumaError.Kind.INVALID_CREDENTIALS, failure.error.kind)
            assertNull(configStore.current())
            assertTrue(secrets.values.isEmpty())
            assertEquals(ConnectionState.NotConfigured, m.state.value)
        }

    @Test
    fun `server with major version other than 2 is rejected`() =
        runTest {
            configureApi = { api ->
                api.postLoginEvents =
                    listOf(KumaEvent.Info(InfoDto(version = "1.23.16"))) +
                    Fixtures.loginBurst().filterNot { it is KumaEvent.Info }
            }
            val m = manager()
            val result = m.login("https://kuma.example", "admin", "pw", null) as LoginResult.Failure
            assertEquals(KumaError.Kind.UNSUPPORTED_SERVER, result.error.kind)
            advanceUntilIdle()
            assertNull(configStore.current())
            assertTrue(secrets.values.isEmpty())
            assertTrue(cache.monitors.isEmpty(), "no data written for unsupported servers")
        }

    @Test
    fun `foreground resumes with the stored token, background closes after the grace period`() =
        runTest {
            secrets.values[TOKEN_KEY] = "jwt-1"
            val m = manager(stored)
            m.setForeground(true)
            advanceUntilIdle()
            val api = apis.single()
            assertTrue("loginByToken:jwt-1" in api.calls)
            assertEquals("2.5.8", connected(m.state.value))
            assertEquals(13, cache.monitors.size)

            m.setForeground(false)
            advanceTimeBy(29_000)
            assertFalse(api.closed)
            m.setForeground(true)
            advanceTimeBy(60_000)
            assertFalse(api.closed, "returning to foreground cancels the grace timer")

            m.setForeground(false)
            advanceTimeBy(30_001)
            assertTrue(api.closed)
            assertEquals(ConnectionState.Disconnected, m.state.value)
            assertNull(m.serverInfo.value)
        }

    @Test
    fun `reconnect logs in again by token`() =
        runTest {
            secrets.values[TOKEN_KEY] = "jwt-1"
            val m = manager(stored)
            m.setForeground(true)
            advanceUntilIdle()
            val api = apis.single()
            api.setState(KumaClientState.Disconnected("transport close", reconnecting = true))
            runCurrent()
            assertEquals(ConnectionState.Connecting, m.state.value)
            api.setState(KumaClientState.Connected)
            runCurrent()
            api.setState(KumaClientState.Ready(autoLogin = false))
            advanceUntilIdle()
            assertEquals(2, api.calls.count { it == "loginByToken:jwt-1" })
            assertEquals("2.5.8", connected(m.state.value))
        }

    @Test
    fun `rejected token or refresh event lead to LoginRequired and clear the token`() =
        runTest {
            secrets.values[TOKEN_KEY] = "stale"
            val m = manager(stored)
            m.setForeground(true)
            advanceUntilIdle()
            assertEquals(ConnectionState.LoginRequired, m.state.value)
            assertNull(secrets.values[TOKEN_KEY])
            assertTrue(apis.single().closed)

            secrets.values[TOKEN_KEY] = "jwt-1"
            m.setForeground(true)
            advanceUntilIdle()
            assertEquals("2.5.8", connected(m.state.value))
            apis.last().emit(KumaEvent.Refresh)
            advanceUntilIdle()
            assertEquals(ConnectionState.LoginRequired, m.state.value)
            assertNull(secrets.values[TOKEN_KEY])
        }

    @Test
    fun `syncOnce connects transiently, waits for the burst and disconnects in background`() =
        runTest {
            val m = manager()
            assertFalse(m.syncOnce(1_000), "not configured")

            configStore.save(stored)
            secrets.values[TOKEN_KEY] = "jwt-1"
            assertTrue(m.syncOnce(10_000))
            assertEquals(13, cache.monitors.size)
            assertEquals(13, cache.beats.size)
            assertTrue(apis.single().closed)

            secrets.values[TOKEN_KEY] = "stale"
            assertFalse(m.syncOnce(10_000), "rejected token")
            assertEquals(ConnectionState.LoginRequired, m.state.value)
        }

    @Test
    fun `syncOnce times out when the burst never completes`() =
        runTest {
            configureApi = { it.postLoginEvents = listOf(Fixtures.first("monitorList")) }
            secrets.values[TOKEN_KEY] = "jwt-1"
            val m = manager(stored)
            val error = assertThrows<KumaException> { m.syncOnce(2_000) }.error
            assertEquals(KumaError.Kind.TIMEOUT, error.kind)
            assertTrue(apis.single().closed)
        }

    @Test
    fun `one-off calls need a token and reuse the live socket`() =
        runTest {
            val m = manager(stored)
            assertEquals(
                KumaError.Kind.UNAUTHORIZED,
                assertThrows<KumaException> { m.withApi { it.pauseMonitor(1) } }.error.kind,
            )
            secrets.values[TOKEN_KEY] = "jwt-1"
            m.setForeground(true)
            advanceUntilIdle()
            m.withApi { it.pauseMonitor(1) }
            m.withApi { it.resumeMonitor(1) }
            assertEquals(1, apis.size)
            assertFalse(apis.single().closed)
            assertTrue(apis.single().calls.containsAll(listOf("pause:1", "resume:1")))
        }

    @Test
    fun `logout runs hooks while still logged in, then clears everything`() =
        runTest {
            secrets.values[TOKEN_KEY] = "jwt-1"
            val m = manager(stored)
            m.setForeground(true)
            advanceUntilIdle()
            m.logout()
            assertEquals(listOf("hook(token=jwt-1)"), events)
            assertTrue("logout" in apis.single().calls)
            assertTrue(apis.single().closed)
            assertTrue(secrets.values.isEmpty())
            assertNull(configStore.current())
            assertTrue(cache.monitors.isEmpty())
            assertEquals(ConnectionState.NotConfigured, m.state.value)
        }

    @Test
    fun `switching server clears the cache`() =
        runTest {
            secrets.values[TOKEN_KEY] = "jwt-1"
            val m = manager(stored)
            m.setForeground(true)
            advanceUntilIdle()
            val clearsBefore = cache.clears
            assertEquals(LoginResult.Success, m.login("https://other.example", "admin", "pw", null))
            assertEquals(clearsBefore + 1, cache.clears)
            assertTrue(apis.first().closed)
            assertEquals("https://other.example", configStore.current()?.baseUrl)
        }

    @Test
    fun `network change in foreground reconnects at once and logs in by token again`() =
        runTest {
            secrets.values[TOKEN_KEY] = "jwt-1"
            network.emit(100L)
            val m = manager(stored)
            m.setForeground(true)
            advanceUntilIdle()
            val api = apis.single()
            assertEquals(1, networkSubscriptions)
            assertFalse("reconnectNow" in api.calls, "the initial network is not a change")

            network.emit(null) // offline: nothing to do, Socket.IO notices on its own
            advanceUntilIdle()
            assertFalse("reconnectNow" in api.calls)

            network.emit(200L) // back online on another network (e.g. Wi-Fi -> mobile)
            runCurrent()
            assertEquals(1, api.calls.count { it == "reconnectNow" })
            assertEquals(ConnectionState.Connecting, m.state.value)
            api.setState(KumaClientState.Connected)
            runCurrent()
            api.setState(KumaClientState.Ready(autoLogin = false))
            advanceUntilIdle()
            assertEquals(2, api.calls.count { it == "loginByToken:jwt-1" })
            assertEquals("2.5.8", connected(m.state.value))
            assertEquals(1, apis.size, "same client, no socket leak")
        }

    @Test
    fun `network changes are ignored in background and the callback is unregistered`() =
        runTest {
            secrets.values[TOKEN_KEY] = "jwt-1"
            network.emit(100L)
            val m = manager(stored)
            m.setForeground(true)
            advanceUntilIdle()
            m.setForeground(false)
            advanceUntilIdle()
            assertEquals(0, networkSubscriptions, "no network callback while in background")
            network.emit(200L)
            advanceUntilIdle()
            assertTrue(apis.none { "reconnectNow" in it.calls })
        }

    @Test
    fun `a dropped transport during a request resets authentication until the token login succeeds`() =
        runTest {
            secrets.values[TOKEN_KEY] = "jwt-1"
            val m = manager(stored)
            m.setForeground(true)
            advanceUntilIdle()
            val api = apis.single()
            api.setState(KumaClientState.ConnectFailed("timeout", reconnecting = true))
            runCurrent()
            assertTrue(m.state.value is ConnectionState.Failed)
            val call = async { runCatching { m.withApi { it.getTags() } } }
            advanceTimeBy(timings.readyTimeoutMillis + 1)
            val error = (call.await().exceptionOrNull() as KumaException).error
            assertEquals(KumaError.Kind.NETWORK, error.kind, "fails with the transport error, not a stale session")
        }
}
