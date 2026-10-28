package net.kirkanos.uptimekuma.core.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.kirkanos.uptimekuma.core.data.connection.ConnectionTimings
import net.kirkanos.uptimekuma.core.data.connection.KumaConnectionManager
import net.kirkanos.uptimekuma.core.data.connection.SocketKumaApiFactory
import net.kirkanos.uptimekuma.core.model.ConnectionState
import net.kirkanos.uptimekuma.core.model.LoginResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * The real socket client + connection manager against the dev Kuma (`infra/docker-compose.dev.yml`),
 * with in-memory cache/stores. Run: `./gradlew :core:data:testDebugUnitTest -Pintegration`.
 */
@Tag("integration")
class ConnectionManagerIntegrationTest {
    private val url = System.getProperty("kuma.url") ?: "http://localhost:3001"
    private val user = System.getProperty("kuma.user") ?: "admin"
    private val password = System.getProperty("kuma.password") ?: "devpassword123!"

    private fun manager(
        scope: CoroutineScope,
        cache: FakeKumaCache,
        secrets: FakeSecretStore,
        config: FakeConfigStore,
    ) = KumaConnectionManager(SocketKumaApiFactory(), cache, config, secrets, emptySet(), scope, ConnectionTimings())

    @Test
    fun `login, live sync, background syncOnce and logout`() =
        runBlocking<Unit> {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val cache = FakeKumaCache()
                val secrets = FakeSecretStore()
                val config = FakeConfigStore()
                val m = manager(scope, cache, secrets, config)
                m.setForeground(true)
                assertEquals(LoginResult.Success, m.login(url, user, password, null))
                val connected = withTimeout(20_000) { m.state.first { it is ConnectionState.Connected } }
                println("state=$connected")
                withTimeout(20_000) {
                    while (cache.monitors.isEmpty() || cache.beats.size < cache.monitors.size ||
                        cache.notifications.isEmpty()
                    ) {
                        delay(100)
                    }
                }
                val beatCount = cache.beats.values.sumOf { it.size }
                println("monitors=${cache.monitors.size} beats=$beatCount maintenance=${cache.maintenance}")
                assertTrue(cache.uptime.isNotEmpty())
                val anyMonitor =
                    cache.monitors.values
                        .first { it.type == "http" }
                        .id
                m.withApi { it.pauseMonitor(anyMonitor) }
                m.withApi { it.resumeMonitor(anyMonitor) }
                assertTrue(m.withApi { it.getMonitorBeats(anyMonitor, 1) }.isNotEmpty())

                // A second "process" with the stored token only, in background.
                val cache2 = FakeKumaCache()
                val m2 = manager(scope, cache2, FakeSecretStore().apply { values.putAll(secrets.values) }, config)
                assertTrue(m2.syncOnce(20_000))
                assertEquals(cache.monitors.keys, cache2.monitors.keys)
                assertEquals(ConnectionState.Disconnected, m2.state.value)
                println("syncOnce: monitors=${cache2.monitors.size} beats=${cache2.beats.values.sumOf { it.size }}")

                m.logout()
                assertEquals(ConnectionState.NotConfigured, m.state.value)
                assertTrue(secrets.values.isEmpty())
            } finally {
                scope.cancel()
            }
        }
}
