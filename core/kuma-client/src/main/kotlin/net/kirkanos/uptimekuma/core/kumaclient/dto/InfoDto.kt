package net.kirkanos.uptimekuma.core.kumaclient.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.kirkanos.uptimekuma.core.model.ServerInfo

/** Payload of the `info` socket event. */
@Serializable
data class InfoDto(
    val version: String,
    @SerialName("latestVersion") val latestVersion: String? = null,
    @SerialName("primaryBaseURL") val primaryBaseUrl: String? = null,
) {
    fun toModel(): ServerInfo =
        ServerInfo(
            version = version,
            latestVersion = latestVersion,
            primaryBaseUrl = primaryBaseUrl,
        )
}
