package net.kirkanos.uptimekuma.core.data.statuspage

import kotlinx.coroutines.test.runTest
import net.kirkanos.uptimekuma.core.model.KumaError
import net.kirkanos.uptimekuma.core.model.KumaException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException

class StatusPageRestClientTest {
    private val requested = mutableListOf<String>()
    private var responder: (String) -> HttpResult = { url ->
        when {
            "/heartbeat/" in url -> StatusPageJsonTest.restRaw("status-page.heartbeat.json")
            url.contains("/incident-history") -> StatusPageJsonTest.restRaw("status-page.incident-history.json")
            else -> StatusPageJsonTest.restRaw("status-page.json")
        }.let { (code, body) -> HttpResult(code, body) }
    }
    private val client =
        StatusPageRestClient { url ->
            requested += url
            responder(url)
        }

    @Test
    fun `urls keep the base sub-path`() =
        runTest {
            val page = client.page("https://host.example/kuma/", "dev")
            client.heartbeats("https://host.example/kuma", "dev")
            client.incidentHistory("https://host.example/kuma", "dev", "2026-10-29 10:00:00")
            assertEquals(
                listOf(
                    "https://host.example/kuma/api/status-page/dev",
                    "https://host.example/kuma/api/status-page/heartbeat/dev",
                    "https://host.example/kuma/api/status-page/dev/incident-history?cursor=2026-10-29%2010%3A00%3A00",
                ),
                requested,
            )
            assertEquals("Dev Status", page.config.title)
        }

    @Test
    fun `404 maps to the server message`() =
        runTest {
            responder = { StatusPageJsonTest.restRaw("status-page.not-found.json").let { (c, b) -> HttpResult(c, b) } }
            val e = assertThrows<KumaException> { client.page("https://h", "does-not-exist") }
            assertEquals(KumaError(KumaError.Kind.SERVER_REJECTED, "Status Page Not Found"), e.error)
        }

    @Test
    fun `html instead of json is not a Kuma status page`() =
        runTest {
            responder = { HttpResult(200, "<!doctype html><html></html>") }
            val e = assertThrows<KumaException> { client.page("https://h", "x") }
            assertEquals(KumaError.Kind.UNSUPPORTED_SERVER, e.error.kind)
        }

    @Test
    fun `other http errors and io failures`() =
        runTest {
            responder = { HttpResult(503, """{"status":"fail","msg":"SQLITE_BUSY"}""") }
            assertEquals(
                "SQLITE_BUSY",
                assertThrows<KumaException> { client.heartbeats("https://h", "x") }.error.message,
            )
            responder = { HttpResult(502, "Bad gateway") }
            assertEquals("HTTP 502", assertThrows<KumaException> { client.heartbeats("https://h", "x") }.error.message)
            responder = { throw IOException("unreachable") }
            assertEquals(
                KumaError.Kind.NETWORK,
                assertThrows<KumaException> { client.heartbeats("https://h", "x") }.error.kind,
            )
        }
}
