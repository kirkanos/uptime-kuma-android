package net.kirkanos.uptimekuma.core.kumaclient

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import net.kirkanos.uptimekuma.core.kumaclient.dto.NotificationDto
import net.kirkanos.uptimekuma.core.kumaclient.json.KumaJson
import net.kirkanos.uptimekuma.core.kumaclient.json.toJsonElement
import net.kirkanos.uptimekuma.core.kumaclient.json.toPlainValue
import net.kirkanos.uptimekuma.core.model.KumaNotification
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.net.URI
import java.time.Instant

class HelpersTest {
    @ParameterizedTest
    @CsvSource(
        "2026-10-28 22:25:47.633, 2026-10-28T22:25:47.633Z",
        "2026-10-28 22:25:47, 2026-10-28T22:25:47Z",
        "2026-10-28T22:25:47.633Z, 2026-10-28T22:25:47.633Z",
        "2026-10-28T23:25:47.633+01:00, 2026-10-28T22:25:47.633Z",
    )
    fun `kuma time strings are UTC`(
        input: String,
        expected: String,
    ) {
        assertEquals(Instant.parse(expected).toEpochMilli(), KumaTime.parseUtcMillis(input))
    }

    @Test
    fun `unparseable times are null`() {
        assertNull(KumaTime.parseUtcMillis(""))
        assertNull(KumaTime.parseUtcMillis(null))
        assertNull(KumaTime.parseUtcMillis("yesterday"))
    }

    @Test
    fun `node certificate dates`() {
        assertEquals(
            Instant.parse("2026-09-06T02:03:04Z").toEpochMilli(),
            KumaTime.parseCertDateMillis("Sep  6 02:03:04 2026 GMT"),
        )
        assertEquals(
            Instant.parse("2026-12-25T22:56:35Z").toEpochMilli(),
            KumaTime.parseCertDateMillis("Dec 25 22:56:35 2026 GMT"),
        )
    }

    @ParameterizedTest
    @CsvSource(
        "status.example.org, https://status.example.org",
        "  https://status.example.org/  , https://status.example.org",
        "http://10.0.0.2:3001/, http://10.0.0.2:3001",
        "example.org/kuma/?x=1#frag, https://example.org/kuma",
    )
    fun `urls are normalized`(
        input: String,
        expected: String,
    ) {
        assertEquals(expected, KumaUrls.normalize(input))
    }

    @Test
    fun `sub path becomes the engine path, not the namespace`() {
        val endpoint = KumaEndpoint.parse("https://example.org/kuma/")
        assertEquals(URI("https://example.org"), endpoint.origin)
        assertEquals("/kuma/socket.io/", endpoint.socketPath)

        val plain = KumaEndpoint.parse("http://localhost:3001")
        assertEquals(URI("http://localhost:3001"), plain.origin)
        assertEquals("/socket.io/", plain.socketPath)

        assertEquals(URI("https://h"), KumaEndpoint.parse("wss://h").origin)
        assertThrows<IllegalArgumentException> { KumaEndpoint.parse("ftp://example.org") }
    }

    @Test
    fun `notification payload merges meta fields over provider fields like the web UI`() {
        val notification =
            KumaNotification(
                id = 4,
                name = "Phone",
                type = "webhook",
                isDefault = true,
                active = true,
                fields =
                    mapOf(
                        "webhookURL" to "https://ntfy.example/upABC?up=1",
                        "webhookContentType" to "custom",
                        "retries" to 3L,
                        "nested" to mapOf("a" to listOf(1L, "b")),
                        "name" to "ignored",
                    ),
            )
        val payload = NotificationConfig.toPayload(notification, applyExisting = true)
        assertEquals(JsonPrimitive("Phone"), payload["name"])
        assertEquals(JsonPrimitive("webhook"), payload["type"])
        assertEquals(JsonPrimitive(true), payload["isDefault"])
        assertEquals(JsonPrimitive(true), payload["applyExisting"])
        assertEquals(JsonPrimitive(true), payload["active"])
        assertEquals(JsonPrimitive("custom"), payload["webhookContentType"])
        assertEquals(mapOf("a" to listOf(1L, "b")), payload.getValue("nested").toPlainValue())

        // Round trip through Kuma's storage format (config = JSON.stringify(payload)).
        val stored = NotificationDto(id = 4, name = "Phone", isDefault = true, config = payload.toString())
        val parsed = NotificationConfig.toModel(stored)
        assertEquals(notification.copy(fields = notification.fields - "name"), parsed)
    }

    @Test
    fun `malformed notification config yields empty fields`() {
        val parsed = NotificationConfig.toModel(NotificationDto(id = 1, name = "x", config = "{not json"))
        assertEquals(emptyMap<String, Any?>(), parsed.fields)
        assertEquals("", parsed.type)
    }

    @Test
    fun `plain value conversion round trips`() {
        val value = mapOf("s" to "x", "l" to 2L, "d" to 1.5, "b" to false, "n" to null, "list" to listOf(1L))
        val element = value.toJsonElement()
        assertEquals(
            value,
            KumaJson.json
                .parseToJsonElement(element.toString())
                .jsonObject
                .toPlainValue(),
        )
    }
}
