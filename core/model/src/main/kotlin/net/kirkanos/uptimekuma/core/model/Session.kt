package net.kirkanos.uptimekuma.core.model

/** The configured Uptime Kuma server. One server per installation for now; keep [id] for multi-server later. */
data class ServerConfig(
    val id: Long = 1,
    /** Base URL without trailing slash, e.g. `https://uptime.example.org` (may include a sub-path). */
    val baseUrl: String,
    val username: String,
)

/** Connection state of the live socket. */
sealed interface ConnectionState {
    /** No server configured yet. */
    data object NotConfigured : ConnectionState

    /** Configured but the socket is closed (app in background, or explicitly stopped). */
    data object Disconnected : ConnectionState

    data object Connecting : ConnectionState

    /** Socket open but the stored token was rejected; the user must log in again. */
    data object LoginRequired : ConnectionState

    data class Connected(
        val serverInfo: ServerInfo,
    ) : ConnectionState

    /** Connection failed; the client retries with backoff. */
    data class Failed(
        val error: KumaError,
        val retryInMillis: Long? = null,
    ) : ConnectionState
}

/** Result of an interactive login attempt. */
sealed interface LoginResult {
    data object Success : LoginResult

    /** The account has 2FA enabled; call login again with a TOTP code. */
    data object TwoFactorRequired : LoginResult

    data class Failure(
        val error: KumaError,
    ) : LoginResult
}

/** Errors surfaced to the UI. [message] is a human readable fallback; map [kind] to localized strings. */
data class KumaError(
    val kind: Kind,
    val message: String? = null,
) {
    enum class Kind {
        /** Host unreachable, DNS, TLS handshake, timeout. */
        NETWORK,

        /** Wrong username/password or invalid TOTP. */
        INVALID_CREDENTIALS,

        /** Kuma rate limit hit (login 20/min, API 60/min). */
        RATE_LIMITED,

        /** Server is not Uptime Kuma or the version is unsupported. */
        UNSUPPORTED_SERVER,

        /** Server needs initial setup in the web UI first. */
        SETUP_REQUIRED,

        /** Operation rejected by the server (`ok:false`), see [message]. */
        SERVER_REJECTED,

        /** Request timed out waiting for an ack. */
        TIMEOUT,

        /** Not logged in / token invalid. */
        UNAUTHORIZED,
        UNKNOWN,
    }
}

/** Thrown by suspend repository calls; carries a [KumaError]. */
class KumaException(
    val error: KumaError,
    cause: Throwable? = null,
) : Exception(error.message ?: error.kind.name, cause)
