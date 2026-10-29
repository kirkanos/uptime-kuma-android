package net.kirkanos.uptimekuma.push.firebase

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class RelayUrlTest {
    @ParameterizedTest
    @ValueSource(
        strings = [
            "https://relay.example.com", "https://relay.example.com/", "HTTPS://relay.example.com:8443/kuma",
            " https://r.example ",
        ],
    )
    fun `https URLs are fine`(url: String) {
        assertEquals(RelayUrl.Check.OK, RelayUrl.check(url))
    }

    @Test
    fun `http works but is flagged`() {
        assertEquals(RelayUrl.Check.INSECURE, RelayUrl.check("http://192.168.1.10:8080"))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "", " ", "relay.example.com", "ftp://relay.example.com", "https://", "https:///path",
            "https://relay.example.com/?a=b", "https://relay.example.com/#x", "https://user:pw@relay.example.com",
            "https://relay example.com", "javascript:alert(1)",
        ],
    )
    fun `everything else is invalid`(url: String) {
        assertEquals(RelayUrl.Check.INVALID, RelayUrl.check(url))
        assertNull(RelayUrl.normalize(url))
    }

    @Test
    fun `normalize trims whitespace and trailing slashes but keeps a sub path`() {
        assertEquals("https://relay.example.com", RelayUrl.normalize(" https://relay.example.com// "))
        assertEquals("https://example.com/relay", RelayUrl.normalize("https://example.com/relay/"))
    }

    @Test
    fun `webhook URL is base plus v1 fcm plus token`() {
        val token = "dXyZ:APA91bH-abc_123"
        assertEquals(
            "https://relay.example.com/v1/fcm/$token",
            RelayUrl.webhookUrl("https://relay.example.com/", token),
        )
        assertEquals("https://example.com/relay/v1/fcm/$token", RelayUrl.webhookUrl("https://example.com/relay", token))
    }
}
