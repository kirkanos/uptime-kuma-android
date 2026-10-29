package net.kirkanos.uptimekuma.core.data.admin

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.kirkanos.uptimekuma.core.data.FakeConfigStore
import net.kirkanos.uptimekuma.core.data.FakeKumaCache
import net.kirkanos.uptimekuma.core.data.FakeSecretStore
import net.kirkanos.uptimekuma.core.data.connection.ConnectionTimings
import net.kirkanos.uptimekuma.core.data.connection.KumaConnectionManager
import net.kirkanos.uptimekuma.core.data.connection.SocketKumaApiFactory
import net.kirkanos.uptimekuma.core.model.ApiKeyDraft
import net.kirkanos.uptimekuma.core.model.ApiKeyStatus
import net.kirkanos.uptimekuma.core.model.ConnectionState
import net.kirkanos.uptimekuma.core.model.DockerHostDraft
import net.kirkanos.uptimekuma.core.model.DockerHostType
import net.kirkanos.uptimekuma.core.model.KumaException
import net.kirkanos.uptimekuma.core.model.LoginResult
import net.kirkanos.uptimekuma.core.model.ProxyDraft
import net.kirkanos.uptimekuma.core.model.ProxyProtocol
import net.kirkanos.uptimekuma.core.model.RemoteBrowserDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

/**
 * Server administration against the dev Kuma (`infra/docker-compose.dev.yml`). Only touches entities named
 * `[P3-ADMIN] …` and restores every changed setting. Run:
 * `./gradlew :core:data:testDebugUnitTest -Pintegration --tests '*ServerAdminIntegrationTest*'`.
 *
 * Opt-in parts (they affect other clients of the shared instance):
 * - env `KUMA_P3ADMIN_APIKEYS=true`: creating an API key makes Kuma set the hidden setting `apiKeysEnabled=true`
 *   for good, after which `/metrics` accepts only API keys (basic auth answers 401) even when all keys are deleted.
 * - env `KUMA_P3ADMIN_SAVE2FA=true`: `save2FA` + immediate `disable2FA`; other logins fail while 2FA is on.
 *
 * Never exercised live: `changePassword` (re-hashing invalidates the JWT of every other client of the shared
 * admin account, even when changed back), `disableAuth`, `clearStatistics` (wipes everyone's history) and
 * `cloudflared_start`.
 */
@Tag("integration")
class ServerAdminIntegrationTest {
    private val url = System.getProperty("kuma.url") ?: "http://localhost:3001"
    private val user = System.getProperty("kuma.user") ?: "admin"
    private val password = System.getProperty("kuma.password") ?: "devpassword123!"
    private val apiKeyRun = System.getenv("KUMA_P3ADMIN_APIKEYS").toBoolean()
    private val save2fa = System.getenv("KUMA_P3ADMIN_SAVE2FA").toBoolean()

    @Test
    fun `settings, api keys, proxies, docker hosts, remote browsers, cloudflared and 2FA`() =
        runBlocking<Unit> {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val secrets = FakeSecretStore()
            val m = manager(scope, secrets)
            val backend = ConnectionAdminBackend(m, secrets)
            val apiKeys = KumaApiKeyRepository(backend)
            val proxies = KumaProxyRepository(backend)
            val dockerHosts = KumaDockerHostRepository(backend)
            val browsers = KumaRemoteBrowserRepository(backend)
            val account = KumaAccountRepository(backend)
            try {
                m.setForeground(true)
                assertEquals(LoginResult.Success, m.login(url, user, password, null))
                withTimeout(TIMEOUT) { m.state.first { it is ConnectionState.Connected } }

                settings(KumaServerSettingsRepository(backend))
                apiKeys.refresh()
                if (apiKeyRun) apiKeyRoundTrip(apiKeys)
                proxyRoundTrip(proxies)
                dockerHostRoundTrip(dockerHosts)
                remoteBrowserRoundTrip(browsers)
                cloudflared(KumaCloudflaredRepository(backend))
                twoFactor(account)
            } finally {
                runCatching { if (account.twoFactorEnabled()) account.disableTwoFactor(password) }
                cleanup(apiKeys, proxies, dockerHosts, browsers)
                m.logout()
                scope.cancel()
            }
            // The admin account is untouched: the original password still logs in without 2FA.
            verifyLogin()
        }

    private suspend fun settings(repo: KumaServerSettingsRepository) {
        val details = withTimeout(TIMEOUT) { repo.observeServerDetails().filterNotNull().first { it.version != null } }
        println("details=$details updateAvailable=${details.updateAvailable}")
        assertNotNull(details.dbType)
        val original = repo.load()
        println("settings=$original")
        try {
            repo.save(original.copy(searchEngineIndex = !original.searchEngineIndex))
            assertEquals(!original.searchEngineIndex, repo.load().searchEngineIndex)
        } finally {
            repo.save(original)
        }
        val restored = repo.load()
        assertEquals(original, restored)
        println("database size=${repo.databaseSize()} bytes")
        println("testChrome=${runCatching { repo.testChrome(original.chromeExecutable) }}")
    }

    private suspend fun apiKeyRoundTrip(apiKeys: KumaApiKeyRepository) {
        val created = apiKeys.create(ApiKeyDraft("$PREFIX key", expires = LocalDateTime.now().plusDays(30).withNano(0)))
        println("api key id=${created.id} prefix=${created.key.take(created.key.indexOf('_') + 1)}")
        assertTrue(created.key.startsWith("uk${created.id}_"))
        try {
            val listed =
                await(apiKeys.observeApiKeys()) { l -> l.any { it.id == created.id } }.first { it.id == created.id }
            println("listed key=$listed")
            assertEquals(ApiKeyStatus.ACTIVE, listed.status)
            assertNotNull(listed.expires)
            apiKeys.setActive(created.id, false)
            await(apiKeys.observeApiKeys()) { l -> l.any { it.id == created.id && it.status == ApiKeyStatus.INACTIVE } }
            apiKeys.setActive(created.id, true)
            await(apiKeys.observeApiKeys()) { l -> l.any { it.id == created.id && it.active } }
        } finally {
            apiKeys.delete(created.id)
        }
        await(apiKeys.observeApiKeys()) { l -> l.none { it.id == created.id } }
    }

    private suspend fun proxyRoundTrip(proxies: KumaProxyRepository) {
        val draft =
            ProxyDraft(
                protocol = ProxyProtocol.SOCKS5,
                host = PROXY_HOST,
                port = 1080,
                auth = true,
                username = "p3",
                password = "secret",
            )
        val id = proxies.save(draft)
        try {
            val proxy = await(proxies.observeProxies()) { l -> l.any { it.id == id } }.first { it.id == id }
            println("proxy=$proxy")
            assertEquals(ProxyProtocol.SOCKS5, proxy.protocol)
            assertTrue(proxy.auth)
            assertFalse(proxy.isDefault)
            assertEquals(id, proxies.save(draft.copy(id = id, port = 1081, auth = false)))
            val edited = await(proxies.observeProxies()) { l -> l.any { it.id == id && it.port == 1081 } }
            println("edited proxy=${edited.first { it.id == id }}")
        } finally {
            proxies.delete(id)
        }
        await(proxies.observeProxies()) { l -> l.none { it.id == id } }
    }

    private suspend fun dockerHostRoundTrip(dockerHosts: KumaDockerHostRepository) {
        val draft = DockerHostDraft(name = "$PREFIX docker", type = DockerHostType.SOCKET)
        println("testDockerHost=${runCatching { dockerHosts.test(draft) }}")
        val id = dockerHosts.save(draft)
        try {
            val hosts = await(dockerHosts.observeDockerHosts()) { l -> l.any { it.id == id } }
            println("docker host=${hosts.first { it.id == id }}")
        } finally {
            dockerHosts.delete(id)
        }
        await(dockerHosts.observeDockerHosts()) { l -> l.none { it.id == id } }
    }

    private suspend fun remoteBrowserRoundTrip(browsers: KumaRemoteBrowserRepository) {
        val draft = RemoteBrowserDraft(name = "$PREFIX browser", url = "ws://p3-admin.invalid:9222/devtools")
        println(
            "testRemoteBrowser=${runCatching {
                browsers.test(
                    draft,
                )
            }.exceptionOrNull()?.message?.lineSequence()?.first()}",
        )
        val id = browsers.save(draft)
        try {
            val listed = await(browsers.observeRemoteBrowsers()) { l -> l.any { it.id == id } }
            println("remote browser=${listed.first { it.id == id }}")
        } finally {
            browsers.delete(id)
        }
        await(browsers.observeRemoteBrowsers()) { l -> l.none { it.id == id } }
    }

    private suspend fun cloudflared(repo: KumaCloudflaredRepository) {
        repo.join()
        val tunnel = withTimeout(TIMEOUT) { repo.observeState().first { it.installed != null } }
        println("cloudflared=$tunnel")
        repo.leave()
    }

    private suspend fun twoFactor(account: KumaAccountRepository) {
        assertFalse(account.twoFactorEnabled())
        val setup = account.prepareTwoFactor(password)
        val secret = requireNotNull(setup.secret)
        println("prepare2FA uri=${setup.uri.substringBefore("secret=")}secret=<${secret.length} chars>")
        val code = Totp.code(secret)
        assertFalse(account.verifyTwoFactorToken(if (code == "000000") "111111" else "000000", password))
        assertTrue(account.verifyTwoFactorToken(code, password))
        println("verifyToken with computed TOTP ok")
        if (save2fa) {
            try {
                account.enableTwoFactor(password)
                assertTrue(account.twoFactorEnabled())
            } finally {
                account.disableTwoFactor(password)
            }
            println("save2FA + disable2FA ok")
        }
        assertFalse(account.twoFactorEnabled())
        try {
            account.prepareTwoFactor("wrong-password")
            error("prepare2FA accepted a wrong password")
        } catch (e: KumaException) {
            println("prepare2FA wrong password -> ${e.error}")
        }
    }

    private suspend fun cleanup(
        apiKeys: KumaApiKeyRepository,
        proxies: KumaProxyRepository,
        dockerHosts: KumaDockerHostRepository,
        browsers: KumaRemoteBrowserRepository,
    ) {
        runCatching {
            apiKeys.observeApiKeys().filterNotNull().first().filter { it.name.startsWith(PREFIX) }.forEach {
                apiKeys.delete(it.id)
            }
            proxies.observeProxies().filterNotNull().first().filter { it.host == PROXY_HOST }.forEach {
                proxies.delete(it.id)
            }
            dockerHosts.observeDockerHosts().filterNotNull().first().filter { it.name.startsWith(PREFIX) }.forEach {
                dockerHosts.delete(it.id)
            }
            browsers.observeRemoteBrowsers().filterNotNull().first().filter { it.name.startsWith(PREFIX) }.forEach {
                browsers.delete(it.id)
            }
        }.onFailure { println("cleanup failed: $it") }
    }

    private suspend fun verifyLogin() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val m = manager(scope, FakeSecretStore())
        try {
            assertEquals(LoginResult.Success, m.login(url, user, password, null))
            println("final login with the original password ok (no 2FA prompt)")
        } finally {
            m.logout()
            scope.cancel()
        }
    }

    private fun manager(
        scope: CoroutineScope,
        secrets: FakeSecretStore,
    ) = KumaConnectionManager(
        SocketKumaApiFactory(),
        FakeKumaCache(),
        FakeConfigStore(),
        secrets,
        emptySet(),
        scope,
        ConnectionTimings(),
    )

    private suspend fun <T> await(
        flow: Flow<List<T>?>,
        predicate: (List<T>) -> Boolean,
    ): List<T> = withTimeout(TIMEOUT) { flow.filterNotNull().first(predicate) }

    private companion object {
        const val PREFIX = "[P3-ADMIN]"
        const val PROXY_HOST = "p3-admin.invalid"
        const val TIMEOUT = 20_000L
    }
}
