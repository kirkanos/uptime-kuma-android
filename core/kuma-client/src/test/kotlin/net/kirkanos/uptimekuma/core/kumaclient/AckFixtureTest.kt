package net.kirkanos.uptimekuma.core.kumaclient

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import net.kirkanos.uptimekuma.core.kumaclient.dto.ChartPointDto
import net.kirkanos.uptimekuma.core.kumaclient.dto.HeartbeatDto
import net.kirkanos.uptimekuma.core.kumaclient.dto.HeartbeatRowDto
import net.kirkanos.uptimekuma.core.kumaclient.dto.TagDto
import net.kirkanos.uptimekuma.core.kumaclient.json.KumaJson
import net.kirkanos.uptimekuma.core.kumaclient.json.asLenientBoolean
import net.kirkanos.uptimekuma.core.kumaclient.json.asLenientLong
import net.kirkanos.uptimekuma.core.model.KumaError
import net.kirkanos.uptimekuma.core.model.KumaException
import net.kirkanos.uptimekuma.core.model.MonitorStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Acknowledgement shapes recorded from Kuma 2.5.8 and their interpretation. */
class AckFixtureTest {
    private fun rejected(name: String): KumaError =
        assertThrows<KumaException> { KumaAcks.requireOk(name, Fixtures.ackResponse(name)) }.error

    @Test
    fun `login success returns the token`() {
        val response = KumaAcks.loginResponse(Fixtures.ackResponse("login.ok"))
        assertTrue(response is LoginResponse.Success)
        assertTrue((response as LoginResponse.Success).token.isNotBlank())
        assertTrue(KumaAcks.loginResponse(Fixtures.ackResponse("login.2fa-ok")) is LoginResponse.Success)
    }

    @Test
    fun `tokenRequired without ok field means two factor`() {
        assertEquals(
            LoginResponse.TwoFactorRequired,
            KumaAcks.loginResponse(Fixtures.ackResponse("login.2fa-token-required")),
        )
    }

    @Test
    fun `wrong credentials and invalid totp map to INVALID_CREDENTIALS`() {
        listOf("login.wrong-password", "login.unknown-user", "login.2fa-invalid-token").forEach { name ->
            val error = assertThrows<KumaException> { KumaAcks.loginResponse(Fixtures.ackResponse(name)) }.error
            assertEquals(KumaError.Kind.INVALID_CREDENTIALS, error.kind, name)
        }
        assertEquals("authIncorrectCreds", rejected("login.wrong-password").message)
    }

    @Test
    fun `loginByToken ok and invalid`() {
        KumaAcks.requireOk("loginByToken", Fixtures.ackResponse("loginByToken.ok"))
        assertEquals(KumaError.Kind.INVALID_CREDENTIALS, rejected("loginByToken.invalid").kind)
    }

    @Test
    fun `needSetup and checkApprise acks are bare booleans, logout has no args`() {
        assertEquals(false, Fixtures.ackResponse("needSetup").asLenientBoolean())
        assertEquals(true, Fixtures.ackResponse("checkApprise").asLenientBoolean())
        assertEquals(0L, Fixtures.json("ack/logout.json")["callbackArgs"].asLenientLong())
    }

    @Test
    fun `plain server errors map to SERVER_REJECTED with message`() {
        val notOwned = rejected("pauseMonitor.not-found")
        assertEquals(KumaError.Kind.SERVER_REJECTED, notOwned.kind)
        assertEquals("You do not own this monitor.", notOwned.message)
        assertEquals("Invalid period.", rejected("getMonitorBeats.invalid-period").message)
        listOf("add.validation-error.interval", "editMonitor.validation-error", "getMonitor.not-found").forEach {
            assertEquals(KumaError.Kind.SERVER_REJECTED, rejected(it).kind, it)
        }
    }

    @Test
    fun `i18n success acks pass`() {
        listOf("pauseMonitor.ok", "resumeMonitor.ok", "deleteMonitor.ok", "add.ok").forEach { name ->
            val ack = KumaAcks.requireOk(name, Fixtures.ackResponse(name))
            assertTrue(KumaAcks.isI18n(ack), name)
        }
    }

    @Test
    fun `getMonitorBeats returns raw rows`() {
        val ack = KumaAcks.requireOk("getMonitorBeats", Fixtures.ackResponse("getMonitorBeats.24h"))
        val rows =
            KumaJson.json.decodeFromJsonElement(
                ListSerializer(HeartbeatRowDto.serializer()),
                ack.getValue("data"),
            )
        val beats = rows.mapNotNull { it.toModel(1) }
        assertEquals(rows.size, beats.size)
        assertTrue(beats.all { it.monitorId == 1L })
        assertEquals(beats.sortedBy { it.timeMillis }, beats)

        val failing = KumaAcks.requireOk("getMonitorBeats", Fixtures.ackResponse("getMonitorBeats.failing-24h"))
        val failingBeats =
            KumaJson.json
                .decodeFromJsonElement(ListSerializer(HeartbeatRowDto.serializer()), failing.getValue("data"))
                .mapNotNull { it.toModel() }
        assertTrue(failingBeats.any { it.status == MonitorStatus.DOWN })
        assertTrue(failingBeats.any { it.status == MonitorStatus.PENDING })
    }

    @Test
    fun `chart data uses unix seconds and is not sorted by the server`() {
        listOf("getMonitorChartData.24h", "getMonitorChartData.720h", "getMonitorChartData.1y").forEach { name ->
            val ack = KumaAcks.requireOk(name, Fixtures.ackResponse(name))
            val points =
                KumaJson.json
                    .decodeFromJsonElement(ListSerializer(ChartPointDto.serializer()), ack.getValue("data"))
                    .map { it.toModel() }
            assertTrue(points.isNotEmpty(), name)
            assertTrue(points.all { it.timeMillis > 1_700_000_000_000L }, name)
        }
        val ack = KumaAcks.requireOk("chart", Fixtures.ackResponse("getMonitorChartData.24h"))
        val first =
            KumaJson.json.decodeFromJsonElement(
                ChartPointDto.serializer(),
                (ack.getValue("data") as JsonArray)[0],
            )
        assertEquals(1_793_227_200_000L, first.toModel().timeMillis)
        assertEquals(89.0, first.toModel().pingMs)
        assertEquals(1, first.toModel().upCount)
    }

    @Test
    fun `important heartbeats are camelCase with important as 0 or 1`() {
        val ack = KumaAcks.requireOk("paged", Fixtures.ackResponse("monitorImportantHeartbeatListPaged.all"))
        val beats =
            KumaJson.json
                .decodeFromJsonElement(ListSerializer(HeartbeatDto.serializer()), ack.getValue("data"))
                .mapNotNull { it.toModel() }
        assertEquals(21, beats.size)
        assertTrue(beats.all { it.important })
        assertEquals(beats.sortedByDescending { it.timeMillis }, beats, "newest first")
        assertEquals(7L, beats.first().monitorId)
        assertNull(beats.first().pingMs)

        val count = KumaAcks.requireOk("count", Fixtures.ackResponse("monitorImportantHeartbeatListCount.all"))
        assertEquals(21L, count["count"].asLenientLong())
    }

    @Test
    fun `getTags and getMonitorMaintenance`() {
        val tags = KumaAcks.requireOk("getTags", Fixtures.ackResponse("getTags"))
        val parsed = KumaJson.json.decodeFromJsonElement(ListSerializer(TagDto.serializer()), tags.getValue("tags"))
        assertEquals(listOf("env", "team"), parsed.map { it.toModel().name })

        val monitors = KumaAcks.requireOk("m", Fixtures.ackResponse("getMonitorMaintenance"))
        assertEquals(
            listOf(10L),
            (monitors.getValue("monitors") as JsonArray).map { it.jsonObject["id"].asLenientLong() },
        )
    }

    @Test
    fun `deleteMonitor of a missing monitor still acks ok`() {
        KumaAcks.requireOk("deleteMonitor", Fixtures.ackResponse("deleteMonitor.not-found"))
    }

    @Test
    fun `add request carries accepted_statuscodes`() {
        val request = Fixtures.ackRequest("add.ok").first().jsonObject
        assertTrue(request.containsKey("accepted_statuscodes"))
        assertFalse(request.containsKey("id"))
        assertEquals(JsonPrimitive("http"), request["type"])
        assertTrue(Fixtures.ackResponse("add.ok") is JsonObject)
    }
}
