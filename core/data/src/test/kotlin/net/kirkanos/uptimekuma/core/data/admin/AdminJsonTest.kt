package net.kirkanos.uptimekuma.core.data.admin

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import net.kirkanos.uptimekuma.core.data.maintenance.MaintenanceFixtures
import net.kirkanos.uptimekuma.core.kumaclient.json.KumaJson
import net.kirkanos.uptimekuma.core.model.ApiKeyDraft
import net.kirkanos.uptimekuma.core.model.ApiKeyStatus
import net.kirkanos.uptimekuma.core.model.DockerHostDraft
import net.kirkanos.uptimekuma.core.model.DockerHostType
import net.kirkanos.uptimekuma.core.model.EntryPage
import net.kirkanos.uptimekuma.core.model.ProxyDraft
import net.kirkanos.uptimekuma.core.model.ProxyProtocol
import net.kirkanos.uptimekuma.core.model.RemoteBrowserDraft
import net.kirkanos.uptimekuma.core.model.ServerSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

private fun parse(text: String): JsonElement = KumaJson.json.parseToJsonElement(text)

class AdminJsonTest {
    @Test
    fun `getSettings fixture of a fresh server gets the web UI defaults`() {
        val data = AdminJson.settingsData(MaintenanceFixtures.ack("getSettings"))
        val settings = AdminJson.decodeSettings(data)
        assertEquals(ServerSettings(serverTimezone = "Europe/Berlin"), settings)
        assertEquals(listOf(7, 14, 21), settings.tlsExpiryNotifyDays)
        assertEquals(180, settings.keepDataPeriodDays)
        assertTrue(settings.nscd)
        assertTrue(settings.checkUpdate)
    }

    @Test
    fun `decodes stored settings leniently`() {
        // Shape of getSettings after the web UI saved once (recorded live from the dev Kuma).
        val data =
            parse(
                """{"serverTimezone":"Europe/Berlin","tlsExpiryNotifyDays":[21,7,"14",0],"domainExpiryNotifyDays":[30],
                "checkBeta":false,"primaryBaseURL":null,"steamAPIKey":"steam","searchEngineIndex":1,
                "entryPage":"statusPage-dev","checkUpdate":true,"disableAuth":false,"trustProxy":true,"nscd":0,
                "keepDataPeriodDays":"30","chromeExecutable":"/usr/bin/chromium","globalpingApiToken":""}""",
            ).jsonObject
        val s = AdminJson.decodeSettings(data)
        assertEquals(listOf(7, 14, 21), s.tlsExpiryNotifyDays)
        assertEquals(listOf(30), s.domainExpiryNotifyDays)
        assertEquals("", s.primaryBaseUrl)
        assertTrue(s.searchEngineIndex)
        assertEquals("dev", EntryPage.slugOf(s.entryPage))
        assertTrue(s.trustProxy)
        assertFalse(s.nscd)
        assertEquals(30, s.keepDataPeriodDays)
        assertEquals("steam", s.steamApiKey)
        assertEquals("/usr/bin/chromium", s.chromeExecutable)
    }

    @Test
    fun `setSettings payload keeps unknown keys, overlays typed ones and pins disableAuth`() {
        val stored =
            parse(
                """{"serverTimezone":"Europe/Berlin","futureKey":"keep me","disableAuth":false,
                "primaryBaseURL":null}""",
            ).jsonObject
        val settings =
            ServerSettings(
                serverTimezone = "UTC",
                searchEngineIndex = true,
                tlsExpiryNotifyDays = listOf(3, 10),
                domainExpiryNotifyDays = null,
                keepDataPeriodDays = 0,
                disableAuth = true,
            )
        val payload = AdminJson.settingsPayload(stored, settings, disableAuth = false)
        assertEquals(JsonPrimitive("keep me"), payload["futureKey"])
        assertEquals(JsonPrimitive("UTC"), payload["serverTimezone"])
        assertEquals(JsonPrimitive(true), payload["searchEngineIndex"])
        assertEquals(JsonArray(listOf(JsonPrimitive(3), JsonPrimitive(10))), payload["tlsExpiryNotifyDays"])
        assertEquals(JsonPrimitive(0), payload["keepDataPeriodDays"])
        assertEquals(JsonPrimitive(false), payload["disableAuth"])
        assertEquals(JsonPrimitive("dashboard"), payload["entryPage"])
        assertFalse("domainExpiryNotifyDays" in payload)
        // Empty text settings that were never set stay unset (null), set ones can be cleared.
        assertEquals(JsonNull, payload["primaryBaseURL"])
        assertFalse("steamAPIKey" in payload)
        val cleared =
            AdminJson.settingsPayload(
                parse("""{"steamAPIKey":"old"}""").jsonObject,
                ServerSettings(steamApiKey = " "),
                disableAuth = false,
            )
        assertEquals(JsonPrimitive(""), cleared["steamAPIKey"])
        // A blank zone never replaces the stored one.
        assertEquals(
            JsonPrimitive("Europe/Berlin"),
            AdminJson.settingsPayload(stored, ServerSettings(serverTimezone = ""), false)["serverTimezone"],
        )
    }

    @Test
    fun `decodes the full info event`() {
        val args =
            MaintenanceFixtures
                .json(
                    "info.json",
                )["samples"]!!
                .let { it as JsonArray }[1]
                .jsonObject["args"] as JsonArray
        val details = AdminJson.decodeDetails(args)!!
        assertEquals("2.5.8", details.version)
        assertEquals("2.5.5", details.latestVersion)
        assertFalse(details.updateAvailable)
        assertTrue(details.isSqlite)
        assertEquals(true, details.isContainer)
        assertEquals("linux", details.runtimePlatform)
        assertEquals("arm64", details.runtimeArch)
        assertEquals("+01:00", details.serverTimezoneOffset)
        assertNull(AdminJson.decodeDetails(null))
    }

    @Test
    fun `decodes the recorded (empty) list snapshots`() {
        assertEquals(emptyList<Any>(), AdminJson.decodeApiKeys(MaintenanceFixtures.eventArgs("apiKeyList")))
        assertEquals(emptyList<Any>(), AdminJson.decodeProxies(MaintenanceFixtures.eventArgs("proxyList")))
        assertEquals(emptyList<Any>(), AdminJson.decodeDockerHosts(MaintenanceFixtures.eventArgs("dockerHostList")))
        assertEquals(
            emptyList<Any>(),
            AdminJson.decodeRemoteBrowsers(MaintenanceFixtures.eventArgs("remoteBrowserList")),
        )
        assertNull(AdminJson.decodeProxies(null))
    }

    @Test
    fun `decodes list rows as pushed by the dev Kuma`() {
        val keys =
            AdminJson.decodeApiKeys(
                listOf(
                    parse(
                        """[{"id":1,"key":"x","name":"[P3-ADMIN] key","userID":1,"createdDate":"2026-10-29 00:31:26",
                        "active":1,"expires":"2026-11-28 01:31","status":"active"},
                        {"id":2,"name":"old","userID":1,"createdDate":"2026-01-01 08:00:00","active":0,"expires":null,
                        "status":"inactive"}]""",
                    ),
                ),
            )!!
        assertEquals(listOf(2L, 1L), keys.map { it.id })
        assertEquals(ApiKeyStatus.ACTIVE, keys[1].status)
        assertTrue(keys[1].active)
        assertEquals(LocalDateTime.of(2026, 11, 28, 1, 31), keys[1].expires)
        assertEquals(LocalDateTime.of(2026, 10, 29, 0, 31, 26), keys[1].createdDate)
        assertNull(keys[0].expires)

        val proxies =
            AdminJson.decodeProxies(
                listOf(
                    parse(
                        """[{"id":3,"user_id":1,"protocol":"socks5","host":"p3-admin.invalid","port":1080,"auth":1,
                        "username":"p3","password":"secret","active":1,"default":0,
                        "created_date":"2026-10-29 00:32:20"}]""",
                    ),
                ),
            )!!
        with(proxies.single()) {
            assertEquals(ProxyProtocol.SOCKS5, protocol)
            assertEquals(1080, port)
            assertTrue(auth)
            assertFalse(isDefault)
            assertEquals("p3", username)
        }

        val docker =
            AdminJson.decodeDockerHosts(
                listOf(
                    parse(
                        """[{"id":5,"userID":1,"dockerDaemon":"/var/run/docker.sock","dockerType":"socket","name":"b"},
                    {"id":6,"userID":1,"dockerDaemon":"tcp://nas:2375","dockerType":"tcp","name":"A"}]""",
                    ),
                ),
            )!!
        assertEquals(listOf("A", "b"), docker.map { it.name })
        assertEquals(DockerHostType.TCP, docker[0].type)

        val browsers = AdminJson.decodeRemoteBrowsers(listOf(parse("""[{"id":3,"url":"ws://x:9222","name":"B"}]""")))!!
        assertEquals("ws://x:9222", browsers.single().url)
    }

    @Test
    fun `payloads match what the web UI sends`() {
        val key = AdminJson.apiKeyPayload(ApiKeyDraft(" Grafana ", LocalDateTime.of(2027, 1, 2, 3, 4), active = true))
        assertEquals(parse("""{"name":"Grafana","expires":"2027-01-02 03:04","active":true}"""), key)
        assertEquals(JsonNull, AdminJson.apiKeyPayload(ApiKeyDraft("k"))["expires"])

        val proxy =
            AdminJson.proxyPayload(
                ProxyDraft(
                    protocol = ProxyProtocol.HTTPS,
                    host = " proxy ",
                    port = 3128,
                    auth = false,
                    username = "u",
                    password = "p",
                ),
            )
        assertEquals(
            parse(
                """{"protocol":"https","host":"proxy","port":3128,"auth":false,"username":null,"password":null,
                "active":true,"default":false,"applyExisting":false}""",
            ),
            proxy,
        )
        val authed =
            AdminJson.proxyPayload(
                ProxyDraft(host = "h", port = 1, auth = true, username = "u", password = "p", isDefault = true),
            )
        assertEquals(JsonPrimitive("u"), authed["username"])
        assertEquals(JsonPrimitive(true), authed["default"])

        assertEquals(
            parse("""{"name":"Local","dockerType":"socket","dockerDaemon":"/var/run/docker.sock"}"""),
            AdminJson.dockerHostPayload(DockerHostDraft(name = "Local ")),
        )
        assertEquals(
            parse("""{"name":"B","url":"ws://x"}"""),
            AdminJson.remoteBrowserPayload(RemoteBrowserDraft(name = "B", url = " ws://x ")),
        )
    }

    private operator fun JsonObject.contains(key: String): Boolean = containsKey(key)
}
