package net.kirkanos.uptimekuma.core.data.statuspage

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import net.kirkanos.uptimekuma.core.data.di.DataDispatcher
import net.kirkanos.uptimekuma.core.kumaclient.json.KumaJson
import net.kirkanos.uptimekuma.core.kumaclient.json.asStringOrNull
import net.kirkanos.uptimekuma.core.model.IncidentHistoryPage
import net.kirkanos.uptimekuma.core.model.KumaError
import net.kirkanos.uptimekuma.core.model.KumaException
import net.kirkanos.uptimekuma.core.model.PublicStatusPage
import net.kirkanos.uptimekuma.core.model.StatusPageHeartbeats
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject

/** Status code and body of a plain HTTP GET. */
internal data class HttpResult(
    val code: Int,
    val body: String,
)

/** Minimal HTTP GET seam so the REST client can be tested without a server. Throws [IOException]. */
internal fun interface HttpGetter {
    suspend fun get(url: String): HttpResult
}

/** [HttpGetter] on `HttpURLConnection` (no extra dependency in `:core:data`). */
internal class UrlConnectionHttpGetter
    @Inject
    constructor(
        @DataDispatcher private val dispatcher: CoroutineDispatcher,
    ) : HttpGetter {
        override suspend fun get(url: String): HttpResult =
            withContext(dispatcher) {
                val connection = URL(url).openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = TIMEOUT_MILLIS
                    connection.readTimeout = TIMEOUT_MILLIS
                    connection.setRequestProperty("Accept", "application/json")
                    val code = connection.responseCode
                    val stream = if (code in HTTP_OK_RANGE) connection.inputStream else connection.errorStream
                    HttpResult(code, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
                } finally {
                    connection.disconnect()
                }
            }

        private companion object {
            const val TIMEOUT_MILLIS = 15_000
        }
    }

private val HTTP_OK_RANGE = 200..299

/**
 * Public status page REST endpoints (`server/routers/status-page-router.js`). [baseUrl] may contain a
 * reverse-proxy sub-path; no authentication. Responses are cached by Kuma (5 min page, 1 min heartbeats).
 */
internal class StatusPageRestClient
    @Inject
    constructor(
        private val http: HttpGetter,
    ) {
        suspend fun page(
            baseUrl: String,
            slug: String,
        ): PublicStatusPage = StatusPageJson.publicPage(getJson(url(baseUrl, "/api/status-page/${encode(slug)}")), slug)

        suspend fun heartbeats(
            baseUrl: String,
            slug: String,
        ): StatusPageHeartbeats =
            StatusPageJson.heartbeats(getJson(url(baseUrl, "/api/status-page/heartbeat/${encode(slug)}")))

        suspend fun incidentHistory(
            baseUrl: String,
            slug: String,
            cursor: String?,
        ): IncidentHistoryPage {
            val query = cursor?.let { "?cursor=${encode(it)}" }.orEmpty()
            return StatusPageJson.history(
                getJson(url(baseUrl, "/api/status-page/${encode(slug)}/incident-history$query")),
            )
        }

        private suspend fun getJson(url: String): JsonObject {
            val result =
                try {
                    http.get(url)
                } catch (e: IOException) {
                    throw KumaException(KumaError(KumaError.Kind.NETWORK, e.message ?: e.javaClass.simpleName), e)
                }
            val json =
                try {
                    KumaJson.json.parseToJsonElement(result.body) as? JsonObject
                } catch (_: SerializationException) {
                    null
                } catch (_: IllegalArgumentException) {
                    null
                }
            if (result.code in HTTP_OK_RANGE && json != null) return json
            val message = json?.get("msg").asStringOrNull()
            throw KumaException(
                when {
                    json == null && result.code in HTTP_OK_RANGE -> {
                        KumaError(KumaError.Kind.UNSUPPORTED_SERVER, "Not an Uptime Kuma status page")
                    }

                    result.code == HTTP_NOT_FOUND -> {
                        KumaError(KumaError.Kind.SERVER_REJECTED, message ?: NOT_FOUND_MESSAGE)
                    }

                    else -> {
                        KumaError(KumaError.Kind.SERVER_REJECTED, message ?: "HTTP ${result.code}")
                    }
                },
            )
        }

        companion object {
            /** Kuma's message for an unknown slug (HTTP 404). */
            const val NOT_FOUND_MESSAGE = "Status Page Not Found"
            private const val HTTP_NOT_FOUND = 404

            internal fun url(
                baseUrl: String,
                path: String,
            ): String = baseUrl.trimEnd('/') + path

            private fun encode(value: String): String =
                URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
        }
    }
