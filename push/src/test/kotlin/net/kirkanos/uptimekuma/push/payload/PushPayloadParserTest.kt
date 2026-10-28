@file:Suppress("MaxLineLength")

package net.kirkanos.uptimekuma.push.payload

import net.kirkanos.uptimekuma.core.model.MonitorStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/** Sample payloads are the rendered outputs from docs/push/README.md section 2.1. */
class PushPayloadParserTest {
    private val down =
        """{"v":1,"monitor":{"id":12,"name":"Web \"prod\" ✓","type":"http","url":"https://example.com/health","hostname":null,"port":null},"heartbeat":{"status":0,"msg":"timeout of 48000ms exceeded","time":"2026-10-28 20:00:00.123","localDateTime":"2026-10-28 21:00:00","ping":null,"duration":60,"retries":2,"important":true},"msg":"[Web \"prod\" ✓] [🔴 Down] timeout of 48000ms exceeded"}"""
    private val up =
        """{"v":1,"monitor":{"id":12,"name":"Web \"prod\" ✓","type":"http","url":"https://example.com/health","hostname":null,"port":null},"heartbeat":{"status":1,"msg":"200 - OK","time":"2026-10-28 20:00:00.123","localDateTime":"2026-10-28 21:00:00","ping":123,"duration":60,"retries":0,"important":true},"msg":"[Web \"prod\" ✓] [✅ Up] 200 - OK"}"""
    private val ping =
        """{"v":1,"monitor":{"id":7,"name":"Router","type":"ping","url":"https://","hostname":"10.0.0.1","port":null},"heartbeat":{"status":0,"msg":"Request timed out","time":"2026-10-28 20:00:00.123","localDateTime":"2026-10-28 21:00:00","ping":null,"duration":60,"retries":2,"important":true},"msg":"[Router] [🔴 Down] Request timed out"}"""
    private val test =
        """{"v":1,"monitor":{"id":null,"name":null,"type":null,"url":null,"hostname":null,"port":null},"heartbeat":{"status":null,"msg":null,"time":null,"localDateTime":null,"ping":null,"duration":null,"retries":null,"important":null},"msg":"My Phone Testing"}"""

    private val expectedTime = Instant.parse("2026-10-28T20:00:00.123Z").toEpochMilli()

    @Test
    fun `doc sample sizes match`() {
        assertEquals(384, down.encodeToByteArray().size)
        assertEquals(342, up.encodeToByteArray().size)
        assertEquals(331, ping.encodeToByteArray().size)
        assertEquals(249, test.encodeToByteArray().size)
    }

    @Test
    fun `parses DOWN`() {
        val event = PushPayloadParser.parse(down.encodeToByteArray())
        assertEquals(12L, event.monitorId)
        assertEquals("Web \"prod\" ✓", event.monitorName)
        assertEquals("http", event.monitorType)
        assertEquals("https://example.com/health", event.target)
        assertEquals(MonitorStatus.DOWN, event.status)
        assertEquals("timeout of 48000ms exceeded", event.message)
        assertEquals(expectedTime, event.timeMillis)
        assertNull(event.pingMs)
        assertTrue(event.important)
    }

    @Test
    fun `parses UP with ping`() {
        val event = PushPayloadParser.parse(up.encodeToByteArray())
        assertEquals(MonitorStatus.UP, event.status)
        assertEquals("200 - OK", event.message)
        assertEquals(123.0, event.pingMs)
    }

    @Test
    fun `ping monitor uses hostname instead of the empty https default url`() {
        val event = PushPayloadParser.parse(ping.encodeToByteArray())
        assertEquals(7L, event.monitorId)
        assertEquals("10.0.0.1", event.target)
        assertEquals(MonitorStatus.DOWN, event.status)
    }

    @Test
    fun `test notification has no monitor and no status`() {
        val event = PushPayloadParser.parse(test.encodeToByteArray())
        assertNull(event.monitorId)
        assertNull(event.monitorName)
        assertNull(event.status)
        assertNull(event.timeMillis)
        assertEquals("My Phone Testing", event.message)
    }

    @Test
    fun `truncated at 4096 bytes inside the heartbeat message`() {
        val longMsg = "x".repeat(5000)
        val full = down.replace("timeout of 48000ms exceeded", longMsg)
        val truncated = full.encodeToByteArray().copyOf(4096)

        val event = PushPayloadParser.parse(truncated)

        assertEquals(12L, event.monitorId)
        assertEquals("Web \"prod\" ✓", event.monitorName)
        assertEquals(MonitorStatus.DOWN, event.status)
        assertTrue(event.message.startsWith("xxxx"))
        assertTrue(event.message.endsWith("…"))
    }

    @Test
    fun `truncated inside the outer msg keeps all heartbeat fields`() {
        val full = up.replace("[✅ Up] 200 - OK\"}", "[✅ Up] " + "y".repeat(5000) + "\"}")
        val event = PushPayloadParser.parse(full.encodeToByteArray().copyOf(4096))

        assertEquals(12L, event.monitorId)
        assertEquals(MonitorStatus.UP, event.status)
        assertEquals("200 - OK", event.message)
        assertEquals(expectedTime, event.timeMillis)
        assertEquals(123.0, event.pingMs)
    }

    @Test
    fun `truncation in the middle of a multibyte character does not throw`() {
        val full = down.replace("timeout of 48000ms exceeded", "ü".repeat(3000))
        val event = PushPayloadParser.parse(full.encodeToByteArray().copyOf(4095))
        assertEquals(12L, event.monitorId)
        assertEquals(MonitorStatus.DOWN, event.status)
    }

    @Test
    fun `unknown schema version is parsed best effort`() {
        val v2 = down.replace("{\"v\":1,", "{\"v\":2,\"extra\":[1,2,3],")
        val event = PushPayloadParser.parse(v2.encodeToByteArray())
        assertEquals(12L, event.monitorId)
        assertEquals(MonitorStatus.DOWN, event.status)
    }

    @Test
    fun `stock Kuma json webhook body is understood too`() {
        val stock =
            """{"heartbeat":{"monitorID":3,"status":1,"msg":"OK","time":"2026-10-28 20:00:00"},""" +
                """"monitor":{"id":3,"name":"API","url":"https://api.example"},"msg":"[API] [✅ Up] OK"}"""
        val event = PushPayloadParser.parse(stock.encodeToByteArray())
        assertEquals(3L, event.monitorId)
        assertEquals(MonitorStatus.UP, event.status)
        assertEquals(Instant.parse("2026-10-28T20:00:00Z").toEpochMilli(), event.timeMillis)
    }

    @Test
    fun `monitor without heartbeat (certificate expiry) keeps id but has no status`() {
        val cert =
            """{"v":1,"monitor":{"id":5,"name":"Shop"},"heartbeat":null,""" +
                """"msg":"[Shop] Certificate expires in 7 days"}"""
        val event = PushPayloadParser.parse(cert.encodeToByteArray())
        assertEquals(5L, event.monitorId)
        assertNull(event.status)
        assertEquals("[Shop] Certificate expires in 7 days", event.message)
    }

    @Test
    fun `plain text becomes the message`() {
        val event = PushPayloadParser.parse("timeout".encodeToByteArray())
        assertNull(event.monitorId)
        assertNull(event.status)
        assertEquals("timeout", event.message)
    }

    @Test
    fun `garbage does not throw`() {
        val garbage = byteArrayOf(0x7b, 0xff.toByte(), 0xfe.toByte(), 0x00, 0x22)
        val event = PushPayloadParser.parse(garbage)
        assertNull(event.monitorId)
        assertNull(event.status)

        assertEquals("", PushPayloadParser.parse(ByteArray(0)).message)
        assertEquals("[1,2]", PushPayloadParser.parse("[1,2]".encodeToByteArray()).message)
        assertEquals("{}", PushPayloadParser.parse("{}".encodeToByteArray()).message)
    }

    @Test
    fun `parses kuma time formats`() {
        assertEquals(expectedTime, PushPayloadParser.parseKumaTime("2026-10-28 20:00:00.123"))
        assertEquals(
            Instant.parse("2026-10-28T20:00:00Z").toEpochMilli(),
            PushPayloadParser.parseKumaTime("2026-10-28 20:00:00"),
        )
        assertEquals(expectedTime, PushPayloadParser.parseKumaTime("2026-10-28T20:00:00.123Z"))
        assertNull(PushPayloadParser.parseKumaTime("yesterday"))
        assertNull(PushPayloadParser.parseKumaTime(null))
    }
}
