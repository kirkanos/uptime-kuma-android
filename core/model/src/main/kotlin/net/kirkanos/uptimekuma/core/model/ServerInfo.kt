package net.kirkanos.uptimekuma.core.model

/**
 * Basic information about the connected Uptime Kuma server, as reported by the
 * `info` socket event.
 */
data class ServerInfo(
    val version: String,
    val latestVersion: String? = null,
    val primaryBaseUrl: String? = null,
) {
    /** True when the server advertises a newer release than the one it is running. */
    val updateAvailable: Boolean
        get() = latestVersion != null && ServerDetails.compareVersions(latestVersion, version) > 0
}
