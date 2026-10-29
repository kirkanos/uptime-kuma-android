package net.kirkanos.uptimekuma.push.payload

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class KumaWebhookNotificationTest {
    @Test
    fun `template matches docs push README section 2_1 byte for byte`() {
        val expected =
            "{\"v\":1,\"monitor\":{\"id\":{{ monitorJSON.id | json | default: \"null\" }}," +
                "\"name\":{{ monitorJSON.name | json | default: \"null\" }}," +
                "\"type\":{{ monitorJSON.type | json | default: \"null\" }}," +
                "\"url\":{{ monitorJSON.url | json | default: \"null\" }}," +
                "\"hostname\":{{ monitorJSON.hostname | json | default: \"null\" }}," +
                "\"port\":{{ monitorJSON.port | json | default: \"null\" }}}," +
                "\"heartbeat\":{\"status\":{{ heartbeatJSON.status | json | default: \"null\" }}," +
                "\"msg\":{{ heartbeatJSON.msg | json | default: \"null\" }}," +
                "\"time\":{{ heartbeatJSON.time | json | default: \"null\" }}," +
                "\"localDateTime\":{{ heartbeatJSON.localDateTime | json | default: \"null\" }}," +
                "\"ping\":{{ heartbeatJSON.ping | json | default: \"null\" }}," +
                "\"duration\":{{ heartbeatJSON.duration | json | default: \"null\" }}," +
                "\"retries\":{{ heartbeatJSON.retries | json | default: \"null\" }}," +
                "\"important\":{{ heartbeatJSON.important | json | default: \"null\" }}}," +
                "\"msg\":{{ msg | json }}}"
        assertEquals(expected, WebhookTemplate.BODY)
        assertEquals(888, WebhookTemplate.BODY.encodeToByteArray().size)
    }

    @Test
    fun `builds the webhook notification exactly as documented`() {
        val n =
            KumaWebhookNotification.build(
                endpoint = "https://ntfy.example.com/upAbCdEfGhIjKl?up=1",
                ntfyToken = " tk_abc ",
                deviceName = "Pixel 8",
            )

        assertNull(n.id)
        assertEquals("Pixel 8 (UnifiedPush)", n.name)
        assertEquals("webhook", n.type)
        assertFalse(n.isDefault)
        assertEquals("https://ntfy.example.com/upAbCdEfGhIjKl?up=1", n.fields["webhookURL"])
        assertEquals("post", n.fields["httpMethod"])
        assertEquals("custom", n.fields["webhookContentType"])
        assertEquals(WebhookTemplate.BODY, n.fields["webhookCustomBody"])
        assertEquals(
            "{\"Authorization\":\"Bearer tk_abc\",\"Content-Type\":\"application/json\"}",
            n.fields["webhookAdditionalHeaders"],
        )
    }

    @Test
    fun `headers are a JSON string that survives special characters`() {
        val headers = KumaWebhookNotification.build("https://x/up1?up=1", "tk_\"quoted\"", "Phone").fields
        val parsed = Json.parseToJsonElement(headers["webhookAdditionalHeaders"] as String).jsonObject
        assertEquals("Bearer tk_\"quoted\"", parsed["Authorization"]!!.jsonPrimitive.content)
    }

    @Test
    fun `relay variant posts the same body to the relay with the relay token`() {
        val ntfy = KumaWebhookNotification.build("https://x/up1?up=1", "tk", "Pixel 8", id = 3, isDefault = true)
        val relay =
            KumaWebhookNotification.buildRelay(
                webhookUrl = "https://relay.example.com/v1/fcm/tok:en",
                relayToken = " relay_secret ",
                deviceName = "Pixel 8",
                id = 3,
                isDefault = true,
            )
        assertEquals("Pixel 8 (Firebase)", relay.name)
        assertEquals(3L, relay.id)
        assertEquals(true, relay.isDefault)
        assertEquals("https://relay.example.com/v1/fcm/tok:en", relay.fields["webhookURL"])
        assertEquals(
            "{\"Authorization\":\"Bearer relay_secret\",\"Content-Type\":\"application/json\"}",
            relay.fields["webhookAdditionalHeaders"],
        )
        // Everything else is identical, so one parser handles both transports.
        val sameKeys = listOf("httpMethod", "webhookContentType", "webhookCustomBody")
        assertEquals(sameKeys.map { ntfy.fields[it] }, sameKeys.map { relay.fields[it] })
        assertEquals(ntfy.type, relay.type)
        assertEquals("Android (Firebase)", KumaWebhookNotification.relayNotificationName(" "))
    }

    @Test
    fun `keeps the id for updates`() {
        assertEquals(42L, KumaWebhookNotification.build("https://x/up1?up=1", "tk", "Phone", id = 42).id)
    }

    @Test
    fun `default flag is passed through`() {
        assertEquals(false, KumaWebhookNotification.build("https://x/up1?up=1", "tk", "Phone").isDefault)
        assertEquals(
            true,
            KumaWebhookNotification.build("https://x/up1?up=1", "tk", "Phone", isDefault = true).isDefault,
        )
    }
}
