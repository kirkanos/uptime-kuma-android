package net.kirkanos.uptimekuma.core.kumaclient

import java.net.URI

/**
 * Where to connect for a Kuma base URL. Socket.IO interprets a URI path as the *namespace*, so the
 * base path (reverse proxy sub-path) must go into the engine path instead: `basePath + "/socket.io/"`.
 */
data class KumaEndpoint(
    /** Scheme + host + port only. */
    val origin: URI,
    /** Engine.IO path, e.g. `/socket.io/` or `/kuma/socket.io/`. */
    val socketPath: String,
) {
    companion object {
        const val SOCKET_PATH: String = "/socket.io/"

        /** @throws IllegalArgumentException for URLs without http(s)/ws(s) scheme or host. */
        fun parse(baseUrl: String): KumaEndpoint {
            val uri =
                runCatching { URI(KumaUrls.normalize(baseUrl)) }
                    .getOrElse { throw IllegalArgumentException("Invalid URL: $baseUrl", it) }
            val scheme =
                when (uri.scheme?.lowercase()) {
                    "http", "ws" -> "http"
                    "https", "wss" -> "https"
                    else -> null
                }
            requireNotNull(scheme) { "Unsupported URL scheme: $baseUrl" }
            val host = requireNotNull(uri.host) { "URL has no host: $baseUrl" }
            val basePath = uri.rawPath.orEmpty().trimEnd('/')
            return KumaEndpoint(
                origin = URI(scheme, null, host, uri.port, null, null, null),
                socketPath = basePath + SOCKET_PATH,
            )
        }
    }
}

/** User input -> canonical base URL. */
object KumaUrls {
    /**
     * Trims, adds `https://` when no scheme is given, drops query/fragment and trailing slashes.
     * `"status.example.org/kuma/"` -> `"https://status.example.org/kuma"`.
     */
    fun normalize(input: String): String {
        var url = input.trim()
        if (!Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(url)) url = "https://$url"
        url = url.substringBefore('#').substringBefore('?')
        return url.trimEnd('/')
    }
}
