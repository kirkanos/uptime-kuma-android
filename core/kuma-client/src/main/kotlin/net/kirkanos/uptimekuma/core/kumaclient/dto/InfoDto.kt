package net.kirkanos.uptimekuma.core.kumaclient.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.kirkanos.uptimekuma.core.model.ServerInfo

/**
 * Payload of the `info` socket event. Sent twice: right after connect without
 * `version`/`latestVersion`/`runtime` (hidden until login), and again after login in full.
 */
@Serializable
data class InfoDto(
    val version: String? = null,
    val latestVersion: String? = null,
    @SerialName("primaryBaseURL") val primaryBaseUrl: String? = null,
    val serverTimezone: String? = null,
    val serverTimezoneOffset: String? = null,
    val isContainer: Boolean? = null,
    val dbType: String? = null,
) {
    /** True for the post-login variant that carries the server version. */
    val isFull: Boolean get() = version != null

    /** `null` for the partial pre-login variant. */
    fun toModel(): ServerInfo? =
        version?.let {
            ServerInfo(
                version = it,
                latestVersion = latestVersion,
                primaryBaseUrl = primaryBaseUrl,
            )
        }
}
