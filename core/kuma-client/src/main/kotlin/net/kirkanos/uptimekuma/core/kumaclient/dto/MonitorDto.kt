package net.kirkanos.uptimekuma.core.kumaclient.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import net.kirkanos.uptimekuma.core.kumaclient.json.LenientBooleanSerializer
import net.kirkanos.uptimekuma.core.kumaclient.json.LenientLongSerializer
import net.kirkanos.uptimekuma.core.kumaclient.json.LenientNullableIntSerializer
import net.kirkanos.uptimekuma.core.kumaclient.json.LenientNullableLongSerializer
import net.kirkanos.uptimekuma.core.kumaclient.json.asLenientBoolean
import net.kirkanos.uptimekuma.core.model.Monitor
import net.kirkanos.uptimekuma.core.model.MonitorTag

private const val DEFAULT_INTERVAL_SECONDS = 60

/**
 * The read-only part of Kuma's `MonitorJSON` (`monitorList`, `updateMonitorIntoList`, `getMonitor`)
 * the app needs. Unknown/sensitive fields are ignored on purpose.
 */
@Serializable
data class MonitorDto(
    @Serializable(with = LenientLongSerializer::class) val id: Long,
    val name: String = "",
    val type: String = "",
    val description: String? = null,
    val pathName: String? = null,
    @Serializable(with = LenientNullableLongSerializer::class) val parent: Long? = null,
    @SerialName("childrenIDs") val childrenIds: List<Long> = emptyList(),
    val url: String? = null,
    val hostname: String? = null,
    @Serializable(with = LenientNullableIntSerializer::class) val port: Int? = null,
    @Serializable(with = LenientBooleanSerializer::class) val active: Boolean = true,
    @Serializable(with = LenientBooleanSerializer::class) val forceInactive: Boolean = false,
    @Serializable(with = LenientNullableIntSerializer::class) val interval: Int? = null,
    @Serializable(with = LenientNullableIntSerializer::class) val weight: Int? = null,
    @Serializable(with = LenientBooleanSerializer::class) val expiryNotification: Boolean = false,
    /** `{ "<notificationId>": true }`. Values are kept raw because Kuma mixes booleans and 0/1. */
    @SerialName("notificationIDList") val notificationIdList: Map<String, JsonElement> = emptyMap(),
    val tags: List<MonitorTagDto> = emptyList(),
    /** True while the monitor (or one of its parents) is covered by an active maintenance. */
    @Serializable(with = LenientBooleanSerializer::class) val maintenance: Boolean = false,
) {
    fun toModel(): Monitor =
        Monitor(
            id = id,
            name = name,
            type = type,
            description = description?.takeIf { it.isNotBlank() },
            url = url,
            hostname = hostname,
            port = port,
            active = active,
            forceInactive = forceInactive,
            parentId = parent,
            childrenIds = childrenIds,
            pathName = pathName?.takeIf { it.isNotBlank() } ?: name,
            intervalSeconds = interval ?: DEFAULT_INTERVAL_SECONDS,
            tags = tags.map { it.toModel() },
            notificationIds = notificationIds(),
            expiryNotification = expiryNotification,
            weight = weight ?: 0,
        )

    fun notificationIds(): Set<Long> =
        notificationIdList
            .filterValues { it.asLenientBoolean() == true }
            .keys
            .mapNotNull { it.toLongOrNull() }
            .toSet()
}

/** One entry of `MonitorJSON.tags` (`monitor_tag` joined with `tag`). */
@Serializable
data class MonitorTagDto(
    @SerialName("tag_id") @Serializable(with = LenientLongSerializer::class) val tagId: Long,
    @SerialName("monitor_id")
    @Serializable(with = LenientNullableLongSerializer::class)
    val monitorId: Long? = null,
    val value: String? = null,
    val name: String = "",
    val color: String = "",
) {
    fun toModel(): MonitorTag = MonitorTag(tagId = tagId, name = name, color = color, value = value.orEmpty())
}
