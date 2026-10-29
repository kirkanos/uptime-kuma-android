package net.kirkanos.uptimekuma.core.model

import java.time.LocalDateTime

/**
 * The server-wide settings of the Kuma web UI's Settings area (`getSettings`/`setSettings`), with the web UI's
 * defaults for keys the server has not stored yet (`src/pages/Settings.vue` `loadSettings`).
 *
 * Only the keys the app edits are typed; the data layer keeps every other stored key when saving.
 */
data class ServerSettings(
    /** IANA zone id, e.g. `Europe/Berlin`. */
    val serverTimezone: String = "",
    /** Base URL the server uses in notifications and links; empty = not set. */
    val primaryBaseUrl: String = "",
    val searchEngineIndex: Boolean = false,
    /** `dashboard` or `statusPage-<slug>`, see [EntryPage]. */
    val entryPage: String = EntryPage.DASHBOARD,
    val steamApiKey: String = "",
    val globalpingApiToken: String = "",
    val nscd: Boolean = true,
    val chromeExecutable: String = "",
    val trustProxy: Boolean = false,
    /** Heartbeats older than this are deleted; `0` keeps them forever. */
    val keepDataPeriodDays: Int = DEFAULT_KEEP_DATA_PERIOD_DAYS,
    val tlsExpiryNotifyDays: List<Int> = DEFAULT_EXPIRY_NOTIFY_DAYS,
    /** `null` when the server does not support domain expiry notifications. */
    val domainExpiryNotifyDays: List<Int>? = DEFAULT_EXPIRY_NOTIFY_DAYS,
    val checkUpdate: Boolean = true,
    val checkBeta: Boolean = false,
    val disableAuth: Boolean = false,
) {
    companion object {
        const val DEFAULT_KEEP_DATA_PERIOD_DAYS = 180
        val DEFAULT_EXPIRY_NOTIFY_DAYS: List<Int> = listOf(7, 14, 21)
    }
}

/** Values of [ServerSettings.entryPage]. */
object EntryPage {
    const val DASHBOARD = "dashboard"
    private const val STATUS_PAGE_PREFIX = "statusPage-"

    fun statusPage(slug: String): String = STATUS_PAGE_PREFIX + slug

    /** The status page slug of [entryPage], `null` for the dashboard. */
    fun slugOf(entryPage: String): String? =
        entryPage.takeIf { it.startsWith(STATUS_PAGE_PREFIX) }?.removePrefix(STATUS_PAGE_PREFIX)
}

/** Everything the post-login `info` event tells about the server (About / Monitor history screens). */
data class ServerDetails(
    val version: String? = null,
    val latestVersion: String? = null,
    val primaryBaseUrl: String? = null,
    val serverTimezone: String? = null,
    val serverTimezoneOffset: String? = null,
    val isContainer: Boolean? = null,
    /** `sqlite` or `mariadb`; `null` before login. */
    val dbType: String? = null,
    val runtimePlatform: String? = null,
    val runtimeArch: String? = null,
) {
    val isSqlite: Boolean get() = dbType.equals(DB_SQLITE, ignoreCase = true)

    /** True only when [latestVersion] is a strictly newer release (Kuma reports older ones too). */
    val updateAvailable: Boolean
        get() {
            val current = version ?: return false
            val latest = latestVersion ?: return false
            return compareVersions(latest, current) > 0
        }

    companion object {
        const val DB_SQLITE = "sqlite"

        /** Compares dotted versions numerically (`2.10.0 > 2.9.1`); a pre-release suffix sorts before the release. */
        fun compareVersions(
            a: String,
            b: String,
        ): Int {
            val pa = a.trim().removePrefix("v").split('-', limit = 2)
            val pb = b.trim().removePrefix("v").split('-', limit = 2)
            val na = pa[0].split('.').map { it.toIntOrNull() ?: 0 }
            val nb = pb[0].split('.').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(na.size, nb.size)) {
                val c = na.getOrElse(i) { 0 }.compareTo(nb.getOrElse(i) { 0 })
                if (c != 0) return c
            }
            val preA = pa.getOrNull(1)
            val preB = pb.getOrNull(1)
            return when {
                preA == null && preB == null -> 0
                preA == null -> 1
                preB == null -> -1
                else -> preA.compareTo(preB)
            }
        }
    }
}

/** Status of an API key as computed by the server (`ApiKey.toJSON`). */
enum class ApiKeyStatus(
    val wire: String,
) {
    ACTIVE("active"),
    INACTIVE("inactive"),
    EXPIRED("expired"),
    UNKNOWN(""),
    ;

    companion object {
        fun fromWire(value: String?): ApiKeyStatus =
            entries.firstOrNull { it.wire == value && it != UNKNOWN } ?: UNKNOWN
    }
}

/** An entry of `apiKeyList` (the key itself is never sent again after creation). */
data class ApiKey(
    val id: Long,
    val name: String,
    val active: Boolean,
    val status: ApiKeyStatus,
    /** Server wall-clock time (UTC), `null` when unknown. */
    val createdDate: LocalDateTime? = null,
    /** `null` = never expires. */
    val expires: LocalDateTime? = null,
)

/** Input of `addAPIKey`. */
data class ApiKeyDraft(
    val name: String,
    /** `null` = never expires. */
    val expires: LocalDateTime? = null,
    val active: Boolean = true,
)

/** Result of `addAPIKey`: the clear-text key (`uk<id>_<key>`), shown exactly once. */
data class CreatedApiKey(
    val id: Long,
    val key: String,
)

/** Proxy protocols offered by the web UI (`ProxyDialog.vue`), in its order. */
enum class ProxyProtocol(
    val wire: String,
) {
    HTTPS("https"),
    HTTP("http"),
    SOCKS("socks"),
    SOCKS5("socks5"),
    SOCKS5H("socks5h"),
    SOCKS4("socks4"),
    ;

    companion object {
        fun fromWire(value: String?): ProxyProtocol = entries.firstOrNull { it.wire == value } ?: HTTP
    }
}

/** An entry of `proxyList`. */
data class Proxy(
    val id: Long,
    val protocol: ProxyProtocol,
    val host: String,
    val port: Int?,
    val auth: Boolean,
    val username: String = "",
    val password: String = "",
    val active: Boolean = true,
    val isDefault: Boolean = false,
) {
    val label: String get() = "${protocol.wire}://$host${port?.let { ":$it" }.orEmpty()}"
}

/** Input of `addProxy`; [id] `null` creates. */
data class ProxyDraft(
    val id: Long? = null,
    val protocol: ProxyProtocol = ProxyProtocol.HTTPS,
    val host: String = "",
    val port: Int? = null,
    val auth: Boolean = false,
    val username: String = "",
    val password: String = "",
    val active: Boolean = true,
    val isDefault: Boolean = false,
    /** One-shot: attach the proxy to every existing monitor. */
    val applyExisting: Boolean = false,
)

enum class DockerHostType(
    val wire: String,
) {
    SOCKET("socket"),
    TCP("tcp"),
    ;

    companion object {
        fun fromWire(value: String?): DockerHostType = entries.firstOrNull { it.wire == value } ?: SOCKET
    }
}

/** An entry of `dockerHostList`. [daemon] is the socket path or the `tcp://`/`http(s)://` URL. */
data class DockerHost(
    val id: Long,
    val name: String,
    val type: DockerHostType,
    val daemon: String,
)

/** Input of `addDockerHost`/`testDockerHost`; [id] `null` creates. */
data class DockerHostDraft(
    val id: Long? = null,
    val name: String = "",
    val type: DockerHostType = DockerHostType.SOCKET,
    val daemon: String = DEFAULT_SOCKET,
) {
    companion object {
        const val DEFAULT_SOCKET = "/var/run/docker.sock"
    }
}

/** An entry of `remoteBrowserList`: a Chrome DevTools websocket endpoint. */
data class RemoteBrowser(
    val id: Long,
    val name: String,
    val url: String,
)

/** Input of `addRemoteBrowser`/`testRemoteBrowser`; [id] `null` creates. */
data class RemoteBrowserDraft(
    val id: Long? = null,
    val name: String = "",
    val url: String = "",
)

/** `prepare2FA` result: the `otpauth://` URI with the freshly generated secret. */
data class TwoFactorSetup(
    val uri: String,
) {
    /** Base32 secret from the URI's `secret` parameter (for manual entry). */
    val secret: String?
        get() =
            uri
                .substringAfter('?', "")
                .split('&')
                .firstOrNull { it.startsWith("secret=") }
                ?.removePrefix("secret=")
                ?.takeIf { it.isNotBlank() }
}

/** State of the server's built-in Cloudflare tunnel (`cloudflared_*` events). */
data class CloudflaredState(
    /** `null` until the server answered; `false` when the `cloudflared` binary is missing. */
    val installed: Boolean? = null,
    val running: Boolean = false,
    /** The stored tunnel token, if any. */
    val token: String? = null,
    val message: String? = null,
    val errorMessage: String? = null,
)
