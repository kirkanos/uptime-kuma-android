package net.kirkanos.uptimekuma.core.data.statuspage

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import net.kirkanos.uptimekuma.core.kumaclient.json.KumaJson
import net.kirkanos.uptimekuma.core.model.IncidentDraft
import net.kirkanos.uptimekuma.core.model.IncidentStyle
import net.kirkanos.uptimekuma.core.model.MonitorStatus
import net.kirkanos.uptimekuma.core.model.OverallStatus
import net.kirkanos.uptimekuma.core.model.StatusPageAnalytics
import net.kirkanos.uptimekuma.core.model.StatusPageGroup
import net.kirkanos.uptimekuma.core.model.StatusPageMonitor
import net.kirkanos.uptimekuma.core.model.StatusPageTheme
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** Status page decoding against the recorded Kuma 2.5.8 payloads and the payloads the web UI sends. */
class StatusPageJsonTest {
    @Test
    fun `statusPageList snapshot decodes the admin config`() {
        val args =
            fixture("statusPageList.json")
                .getValue("samples")
                .jsonArray[0]
                .jsonObject
                .getValue("args")
                .jsonArray
        val list = StatusPageJson.configList(args.toList())!!
        val dev = list.single()
        assertEquals(1L, dev.id)
        assertEquals("dev", dev.slug)
        assertEquals("Dev Status", dev.title)
        assertEquals("Local dev status page used for Android client fixtures.", dev.description)
        assertEquals("/icon.svg", dev.icon)
        assertEquals(StatusPageTheme.AUTO, dev.theme)
        assertEquals(300, dev.autoRefreshInterval)
        assertTrue(dev.published)
        assertTrue(dev.showTags)
        assertTrue(dev.showCertificateExpiry)
        assertFalse(dev.showOnlyLastHeartbeat)
        assertTrue(dev.showPoweredBy)
        assertEquals("kuma-seed fixtures", dev.footerText)
        assertNull(dev.customCss)
        assertNull(dev.analyticsType)
        assertEquals(emptyList<String>(), dev.domainNameList)
    }

    @Test
    fun `missing snapshot is null and pages are sorted by title`() {
        assertNull(StatusPageJson.configList(null))
        val obj =
            KumaJson.json.parseToJsonElement(
                """{"2":{"id":2,"slug":"b","title":"beta"},"1":{"id":1,"slug":"a","title":"Zeta"},
                   |"3":{"id":3,"slug":"c","title":"Alpha"}}
                """.trimMargin(),
            )
        assertEquals(listOf("c", "b", "a"), StatusPageJson.configList(listOf(obj))!!.map { it.slug })
    }

    @Test
    fun `getStatusPage ack config decodes`() {
        val config = StatusPageJson.config(ack("getStatusPage.ok.json").getValue("config").jsonObject)
        assertEquals("dev", config.slug)
        assertEquals(1L, config.id)
    }

    @Test
    fun `public status page REST body decodes groups, monitors and maintenance`() {
        val page = StatusPageJson.publicPage(rest("status-page.json"), "dev")
        assertEquals("dev", page.config.slug)
        assertNull(page.config.id, "public config has no id")
        assertEquals(emptyList<Any>(), page.incidents)
        val group = page.groups.single()
        assertEquals(1L, group.id)
        assertEquals("Core", group.name)
        assertEquals(listOf(1L, 4L), group.monitors.map { it.id })
        val http = group.monitors[0]
        assertEquals("HTTP example.com", http.name)
        assertEquals("http", http.type)
        assertFalse(http.sendUrl)
        assertNull(http.clickableUrl)
        assertEquals(58, http.certExpiryDaysRemaining)
        assertEquals(true, http.validCert)
        val tcp = group.monitors[1]
        assertNull(tcp.certExpiryDaysRemaining, "\"\" for non-TLS monitors")
        assertEquals(false, tcp.validCert)
        val maintenance = page.maintenances.single()
        assertEquals("Dev maintenance (single)", maintenance.title)
        assertEquals("under-maintenance", maintenance.status)
    }

    @Test
    fun `heartbeat REST body decodes beats oldest first and 24h uptime`() {
        val beats = StatusPageJson.heartbeats(rest("status-page.heartbeat.json"))
        assertEquals(setOf(1L, 4L), beats.beats.keys)
        val list = beats.beats.getValue(1L)
        assertEquals(44, list.size)
        assertTrue(list.zipWithNext().all { (a, b) -> a.timeMillis <= b.timeMillis })
        assertEquals(MonitorStatus.UP, list.first().status)
        assertEquals(97.0, list.first().pingMs)
        assertEquals(1L, list.first().monitorId)
        // "2026-10-28 22:25:47.633" is UTC
        assertEquals(1_793_226_347_633L, list.first().timeMillis)
        assertEquals(mapOf(1L to 1.0, 4L to 1.0), beats.uptime24h)
        assertEquals(OverallStatus.ALL_UP, beats.overallStatus)
    }

    @Test
    fun `incident history from REST and socket ack`() {
        val rest = StatusPageJson.history(rest("status-page.incident-history.json"))
        assertEquals(0, rest.total)
        assertFalse(rest.hasMore)
        assertNull(rest.nextCursor)
        val socket = StatusPageJson.history(ack("getIncidentHistory.json"))
        assertEquals(emptyList<Any>(), socket.incidents)
    }

    @Test
    fun `incident decodes IncidentPublic`() {
        val incident =
            StatusPageJson.incident(
                KumaJson.json
                    .parseToJsonElement(
                        """{"id":3,"style":"danger","title":"Down","content":"**x**","pin":true,"active":true,
                        |"createdDate":"2026-10-29 10:00:00","lastUpdatedDate":null,"status_page_id":2}
                        """.trimMargin(),
                    ).jsonObject,
            )
        assertEquals(3L, incident.id)
        assertEquals(IncidentStyle.DANGER, incident.style)
        assertEquals("**x**", incident.content)
        assertTrue(incident.pin && incident.active)
        assertEquals(1_793_268_000_000L, incident.createdMillis)
        assertNull(incident.lastUpdatedMillis)
    }

    @Test
    fun `saveStatusPage config payload echoes the admin config with edits like the web UI`() {
        val raw = ack("getStatusPage.ok.json").getValue("config").jsonObject
        val edited =
            StatusPageJson.config(raw).copy(
                slug = " Dev-Renamed ",
                title = " New title ",
                description = "desc",
                footerText = "foot",
                theme = StatusPageTheme.DARK,
                autoRefreshInterval = 60,
                showTags = false,
                showPoweredBy = false,
                showCertificateExpiry = false,
                showOnlyLastHeartbeat = true,
                customCss = "body {}",
                domainNameList = listOf(" status.example.org ", " ", "b.example.org"),
            )
        val payload = StatusPageJson.configPayload(raw, edited)
        assertEquals(raw.keys, payload.keys, "same fields as getStatusPage, nothing dropped or invented")
        assertEquals("dev-renamed", payload.str("slug"))
        assertEquals("New title", payload.str("title"))
        assertEquals("desc", payload.str("description"))
        assertEquals("foot", payload.str("footerText"))
        assertEquals("dark", payload.str("theme"))
        assertEquals(
            60,
            payload
                .getValue("autoRefreshInterval")
                .jsonPrimitive.content
                .toInt(),
        )
        assertEquals("false", payload.getValue("showTags").jsonPrimitive.content)
        assertEquals("false", payload.getValue("showPoweredBy").jsonPrimitive.content)
        assertEquals("true", payload.getValue("showOnlyLastHeartbeat").jsonPrimitive.content)
        assertEquals("body {}", payload.str("customCSS"))
        assertEquals("/icon.svg", payload.str("icon"), "logo kept")
        assertEquals(
            1L,
            payload
                .getValue("id")
                .jsonPrimitive.content
                .toLong(),
        )
        assertEquals(JsonNull, payload.getValue("analyticsType"), "must be present as null")
        assertEquals(JsonNull, payload.getValue("analyticsId"))
        assertEquals(
            listOf("status.example.org", "b.example.org"),
            payload.getValue("domainNameList").jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `analytics fields follow the selected provider`() {
        val base = StatusPageJson.config(ack("getStatusPage.ok.json").getValue("config").jsonObject)
        val google =
            StatusPageJson.configPayload(
                null,
                base.copy(
                    analyticsType = StatusPageAnalytics.GOOGLE,
                    analyticsId = " G-1 ",
                    analyticsScriptUrl = "https://x",
                ),
            )
        assertEquals("google", google.str("analyticsType"))
        assertEquals("G-1", google.str("analyticsId"))
        assertEquals(JsonNull, google.getValue("analyticsScriptUrl"), "Google takes no script URL")
        val umami =
            StatusPageJson.configPayload(
                null,
                base.copy(analyticsType = StatusPageAnalytics.UMAMI, analyticsScriptUrl = "https://u/s.js"),
            )
        assertEquals("umami", umami.str("analyticsType"))
        assertEquals("https://u/s.js", umami.str("analyticsScriptUrl"))
    }

    @Test
    fun `imgDataUrl keeps the existing logo and falls back to the web UI default`() {
        val base = StatusPageJson.config(ack("getStatusPage.ok.json").getValue("config").jsonObject)
        assertEquals("/icon.svg", StatusPageJson.imgDataUrl(base))
        assertEquals("/upload/logo1.png?t=1", StatusPageJson.imgDataUrl(base.copy(icon = "/upload/logo1.png?t=1")))
        assertEquals("/icon.svg", StatusPageJson.imgDataUrl(base.copy(icon = "")))
        assertEquals("/icon.svg", StatusPageJson.imgDataUrl(base.copy(icon = null)))
    }

    @Test
    fun `publicGroupList payload has id, name and monitorList of id, sendUrl, url`() {
        val payload =
            StatusPageJson.groupsPayload(
                listOf(
                    StatusPageGroup(
                        id = 7,
                        name = " Services ",
                        monitors =
                            listOf(
                                StatusPageMonitor(5, "Site", "http", sendUrl = true, url = "https://custom.example"),
                                StatusPageMonitor(6, "Db", "port"),
                            ),
                    ),
                    StatusPageGroup(name = "New"),
                ),
            )
        val expected =
            KumaJson.json.parseToJsonElement(
                """[{"id":7,"name":"Services","monitorList":[{"id":5,"sendUrl":true,"url":"https://custom.example"},
                   |{"id":6,"sendUrl":false}]},{"name":"New","monitorList":[]}]
                """.trimMargin(),
            )
        assertEquals(expected, payload)
    }

    @Test
    fun `saved group ids are taken from the ack`() {
        val sent = listOf(StatusPageGroup(name = "A"), StatusPageGroup(id = 3, name = "B"))
        val ack =
            buildJsonObject {
                put("ok", true)
                put(
                    "publicGroupList",
                    JsonArray(listOf(buildJsonObject { put("id", 11) }, buildJsonObject { put("id", 3) })),
                )
            }
        assertEquals(listOf(11L, 3L), StatusPageJson.savedGroups(ack, sent).map { it.id })
        assertEquals(sent, StatusPageJson.savedGroups(buildJsonObject { put("ok", true) }, sent))
    }

    @Test
    fun `postIncident payload`() {
        assertEquals(
            KumaJson.json.parseToJsonElement("""{"title":"T","content":"C","style":"warning"}"""),
            StatusPageJson.incidentPayload(IncidentDraft(" T ", "C\n", IncidentStyle.WARNING)),
        )
        assertEquals(
            "4",
            StatusPageJson
                .incidentPayload(IncidentDraft("T", "C", id = 4))
                .getValue("id")
                .jsonPrimitive.content,
        )
        assertEquals(
            "primary",
            StatusPageJson.incidentPayload(IncidentDraft("T", "C")).str("style"),
            "web UI default style",
        )
    }

    private fun JsonObject.str(key: String): String? =
        (get(key) as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

    companion object {
        private val dir: File by lazy {
            val configured = System.getProperty("kuma.fixtures")?.let(::File)
            listOfNotNull(configured, File("../../fixtures/kuma-2.5.8")).first { it.isDirectory }
        }

        fun fixture(name: String): JsonObject = KumaJson.json.parseToJsonElement(File(dir, name).readText()).jsonObject

        fun ack(name: String): JsonObject = fixture("ack/$name").getValue("response").jsonObject

        fun rest(name: String): JsonObject = fixture("rest/$name").getValue("body").jsonObject

        fun restRaw(name: String): Pair<Int, String> {
            val obj = fixture("rest/$name")
            return obj
                .getValue("status")
                .jsonPrimitive.content
                .toInt() to obj.getValue("body").toString()
        }
    }
}
