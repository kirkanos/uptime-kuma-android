package net.kirkanos.uptimekuma.core.data.admin

import app.cash.turbine.test
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import net.kirkanos.uptimekuma.core.data.maintenance.MaintenanceFixtures
import net.kirkanos.uptimekuma.core.kumaclient.KumaAcks
import net.kirkanos.uptimekuma.core.kumaclient.json.KumaJson
import net.kirkanos.uptimekuma.core.model.ApiKeyDraft
import net.kirkanos.uptimekuma.core.model.CloudflaredState
import net.kirkanos.uptimekuma.core.model.KumaError
import net.kirkanos.uptimekuma.core.model.KumaException
import net.kirkanos.uptimekuma.core.model.ProxyDraft
import net.kirkanos.uptimekuma.core.model.ServerSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

private fun obj(text: String): JsonObject = KumaJson.json.parseToJsonElement(text).jsonObject

internal class FakeAdminBackend : AdminBackend {
    val snapshots = mutableMapOf<String, MutableStateFlow<List<JsonElement>?>>()
    val calls = mutableListOf<Pair<String, List<Any?>>>()
    val acks = mutableMapOf<String, JsonObject>()
    val unacked = mutableListOf<Pair<String, List<Any?>>>()
    var storedToken: String? = null

    fun push(
        event: String,
        vararg args: JsonElement,
    ) {
        snapshots.getOrPut(event) { MutableStateFlow(null) }.value = args.toList()
    }

    override fun snapshot(event: String): Flow<List<JsonElement>?> =
        snapshots.getOrPut(event) { MutableStateFlow(null) }

    override suspend fun call(
        event: String,
        vararg args: Any?,
        timeoutMillis: Long,
    ): JsonObject {
        calls += event to args.toList()
        return KumaAcks.requireOk(event, acks[event] ?: obj("""{"ok":true}"""))
    }

    override suspend fun emitUnacknowledged(
        event: String,
        vararg args: Any?,
        graceMillis: Long,
    ) {
        unacked += event to args.toList()
    }

    override suspend fun storeSessionToken(token: String) {
        storedToken = token
    }
}

class AdminRepositoriesTest {
    private val backend = FakeAdminBackend()

    @Test
    fun `save re-reads the stored settings and never changes disableAuth`() =
        runTest {
            backend.acks["getSettings"] =
                obj("""{"ok":true,"data":{"serverTimezone":"Europe/Berlin","disableAuth":false,"x":1}}""")
            val repo = KumaServerSettingsRepository(backend)
            repo.save(ServerSettings(serverTimezone = "UTC", disableAuth = true, searchEngineIndex = true))
            assertEquals(listOf("getSettings", "setSettings"), backend.calls.map { it.first })
            val (payload, password) = backend.calls[1].second
            payload as JsonObject
            assertEquals(JsonPrimitive(false), payload["disableAuth"])
            assertEquals(JsonPrimitive(1), payload["x"])
            assertEquals(JsonPrimitive("UTC"), payload["serverTimezone"])
            assertNull(password)
        }

    @Test
    fun `disabling auth sends the password, enabling does not need it`() =
        runTest {
            backend.acks["getSettings"] = MaintenanceFixtures.ack("getSettings")
            val repo = KumaServerSettingsRepository(backend)
            repo.setAuthDisabled(true, "pw")
            val args = backend.calls.last().second
            assertEquals(JsonPrimitive(true), (args[0] as JsonObject)["disableAuth"])
            assertEquals("pw", args[1])
        }

    @Test
    fun `database size, testChrome and server details`() =
        runTest {
            backend.acks["getDatabaseSize"] = obj("""{"ok":true,"size":983040}""")
            backend.acks["testChrome"] =
                obj("""{"ok":true,"msg":{"key":"foundChromiumVersion","values":["155.0.8059.79"]},"msgi18n":true}""")
            val repo = KumaServerSettingsRepository(backend)
            assertEquals(983_040L, repo.databaseSize())
            assertEquals("155.0.8059.79", repo.testChrome(" /usr/bin/chromium "))
            assertEquals(listOf("/usr/bin/chromium"), backend.calls.last().second)
            repo.observeServerDetails().test {
                assertNull(awaitItem())
                backend.push("info", obj("""{"version":"2.5.8","dbType":"mariadb"}"""))
                assertEquals("mariadb", awaitItem()?.dbType)
            }
        }

    @Test
    fun `changePassword stores the new token`() =
        runTest {
            backend.acks["changePassword"] =
                obj("""{"ok":true,"token":"new.jwt","msg":"successAuthChangePassword","msgi18n":true}""")
            KumaAccountRepository(backend).changePassword("old", "new")
            assertEquals("new.jwt", backend.storedToken)
            val payload =
                backend.calls
                    .single()
                    .second
                    .single() as JsonObject
            assertEquals(obj("""{"currentPassword":"old","newPassword":"new"}"""), payload)
        }

    @Test
    fun `changePassword failure keeps the token`() =
        runTest {
            backend.acks["changePassword"] = obj("""{"ok":false,"msg":"passwordTooWeak","msgi18n":true}""")
            val e = assertThrows<KumaException> { KumaAccountRepository(backend).changePassword("old", "x") }
            assertEquals("passwordTooWeak", e.error.message)
            assertNull(backend.storedToken)
        }

    @Test
    fun `2FA acks from the fixtures`() =
        runTest {
            val account = KumaAccountRepository(backend)
            backend.acks["twoFAStatus"] = MaintenanceFixtures.ack("twoFAStatus.enabled")
            assertTrue(account.twoFactorEnabled())
            backend.acks["twoFAStatus"] = MaintenanceFixtures.ack("twoFAStatus.disabled")
            assertFalse(account.twoFactorEnabled())
            backend.acks["prepare2FA"] = MaintenanceFixtures.ack("prepare2FA.ok")
            assertEquals("REDACTED", account.prepareTwoFactor("pw").secret)
            backend.acks["verifyToken"] = MaintenanceFixtures.ack("verifyToken.ok")
            assertTrue(account.verifyTwoFactorToken(" 271446 ", "pw"))
            assertEquals(listOf("271446", "pw"), backend.calls.last().second)
            backend.acks["verifyToken"] = MaintenanceFixtures.ack("verifyToken.invalid")
            assertFalse(account.verifyTwoFactorToken("000000", "pw"))
            backend.acks["verifyToken"] = obj("""{"ok":false,"msg":"Incorrect current password"}""")
            assertThrows<KumaException> { account.verifyTwoFactorToken("000000", "wrong") }
            backend.acks["save2FA"] = MaintenanceFixtures.ack("save2FA.ok")
            account.enableTwoFactor("pw")
            backend.acks["disable2FA"] = MaintenanceFixtures.ack("disable2FA.ok")
            account.disableTwoFactor("pw")
            assertEquals(listOf("pw"), backend.calls.last().second)
        }

    @Test
    fun `api keys - create returns the clear key once, toggles and deletes`() =
        runTest {
            backend.acks["addAPIKey"] =
                obj("""{"ok":true,"msg":"successAdded","msgi18n":true,"key":"uk7_abc","keyID":7}""")
            val repo = KumaApiKeyRepository(backend)
            val created = repo.create(ApiKeyDraft("k"))
            assertEquals(7L, created.id)
            assertEquals("uk7_abc", created.key)
            backend.acks["addAPIKey"] = obj("""{"ok":true,"key":"uk9_x"}""")
            assertEquals(9L, repo.create(ApiKeyDraft("k")).id)
            repo.setActive(7, false)
            repo.setActive(7, true)
            repo.delete(7)
            repo.refresh()
            assertEquals(
                listOf("addAPIKey", "addAPIKey", "disableAPIKey", "enableAPIKey", "deleteAPIKey", "getAPIKeyList"),
                backend.calls.map { it.first },
            )
        }

    @Test
    fun `proxy, docker host and remote browser events`() =
        runTest {
            backend.acks["addProxy"] = obj("""{"ok":true,"msg":"Saved.","msgi18n":true,"id":3}""")
            assertEquals(3L, KumaProxyRepository(backend).save(ProxyDraft(id = 3, host = "h", port = 1)))
            assertEquals(3L, backend.calls.last().second[1])
            backend.acks["testDockerHost"] =
                obj("""{"ok":true,"msg":"Connected Successfully. Amount of containers: 2"}""")
            assertEquals(
                "Connected Successfully. Amount of containers: 2",
                KumaDockerHostRepository(backend).test(
                    net.kirkanos.uptimekuma.core.model
                        .DockerHostDraft(name = "d"),
                ),
            )
            backend.acks["testRemoteBrowser"] = obj("""{"ok":false,"msg":"browserType.connect: failed"}""")
            val e =
                assertThrows<KumaException> {
                    KumaRemoteBrowserRepository(
                        backend,
                    ).test(
                        net.kirkanos.uptimekuma.core.model
                            .RemoteBrowserDraft(name = "b", url = "ws://x"),
                    )
                }
            assertEquals(KumaError.Kind.SERVER_REJECTED, e.error.kind)
            backend.acks["addRemoteBrowser"] = obj("""{"ok":true}""")
            assertThrows<KumaException> {
                KumaRemoteBrowserRepository(
                    backend,
                ).save(
                    net.kirkanos.uptimekuma.core.model
                        .RemoteBrowserDraft(null, "b", "ws://x"),
                )
            }
        }

    @Test
    fun `cloudflared state is combined from its events`() =
        runTest {
            val repo = KumaCloudflaredRepository(backend)
            repo.observeState().test {
                assertEquals(CloudflaredState(), awaitItem())
                backend.push("cloudflared_installed", JsonPrimitive(true))
                assertEquals(CloudflaredState(installed = true), awaitItem())
                backend.push("cloudflared_running", JsonPrimitive(true))
                backend.push("cloudflared_token", JsonPrimitive("tok"))
                backend.push("cloudflared_errorMessage", JsonPrimitive(""))
                assertEquals(CloudflaredState(installed = true, running = true, token = "tok"), expectMostRecentItem())
            }
            repo.join()
            repo.start(" tok ")
            repo.stop("pw")
            repo.removeToken()
            repo.leave()
            assertEquals(
                listOf(
                    "cloudflared_join" to emptyList(),
                    "cloudflared_start" to listOf("tok"),
                    "cloudflared_stop" to listOf("pw"),
                    "cloudflared_removeToken" to emptyList(),
                    "cloudflared_leave" to emptyList<Any?>(),
                ),
                backend.unacked,
            )
        }
}
