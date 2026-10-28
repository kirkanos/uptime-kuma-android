package net.kirkanos.uptimekuma.core.kumaclient.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.kirkanos.uptimekuma.core.kumaclient.KumaTime
import net.kirkanos.uptimekuma.core.kumaclient.NotificationConfig
import net.kirkanos.uptimekuma.core.kumaclient.json.LenientBooleanSerializer
import net.kirkanos.uptimekuma.core.kumaclient.json.LenientLongSerializer
import net.kirkanos.uptimekuma.core.kumaclient.json.LenientNullableIntSerializer
import net.kirkanos.uptimekuma.core.kumaclient.json.LenientNullableLongSerializer
import net.kirkanos.uptimekuma.core.kumaclient.json.asStringOrNull
import net.kirkanos.uptimekuma.core.model.CertInfo
import net.kirkanos.uptimekuma.core.model.KumaNotification
import net.kirkanos.uptimekuma.core.model.Tag

/** The `certInfo` event's second argument after the second `JSON.parse`. */
@Serializable
data class CertInfoPayloadDto(
    @Serializable(with = LenientBooleanSerializer::class) val valid: Boolean = false,
    val certInfo: CertDetailsDto? = null,
    val hostnameMatchMonitorUrl: Boolean? = null,
) {
    fun toModel(monitorId: Long): CertInfo =
        CertInfo(
            monitorId = monitorId,
            valid = valid,
            subject = certInfo?.subject.distinguishedName(),
            issuer = certInfo?.issuer.distinguishedName(),
            validFromMillis = KumaTime.parseCertDateMillis(certInfo?.validFromRaw),
            validToMillis =
                KumaTime.parseUtcMillis(certInfo?.validTo) ?: KumaTime.parseCertDateMillis(certInfo?.validToRaw),
            daysRemaining = certInfo?.daysRemaining,
            fingerprint256 = certInfo?.fingerprint256,
        )
}

/** Prefers CN, then O, then the first attribute. Node may send multi-valued attributes as arrays. */
private fun JsonElement?.distinguishedName(): String? {
    val obj = this as? JsonObject ?: return asStringOrNull()

    fun attr(key: String): String? =
        when (val value = obj[key]) {
            is JsonArray -> value.firstOrNull().asStringOrNull()
            is JsonPrimitive -> value.asStringOrNull()
            else -> null
        }
    return attr("CN") ?: attr("O") ?: obj.keys.firstNotNullOfOrNull { attr(it) }
}

/** Node's `getPeerCertificate(true)` object as augmented by Kuma (only the fields we use). */
@Serializable
data class CertDetailsDto(
    val subject: JsonElement? = null,
    val issuer: JsonElement? = null,
    @SerialName("valid_from") val validFromRaw: String? = null,
    @SerialName("valid_to") val validToRaw: String? = null,
    /** ISO-8601 string added by Kuma. */
    val validTo: String? = null,
    @Serializable(with = LenientNullableIntSerializer::class) val daysRemaining: Int? = null,
    val fingerprint256: String? = null,
    val certType: String? = null,
    val issuerCertificate: CertDetailsDto? = null,
)

/** One entry of the `maintenanceList` event (object keyed by id). Global, not filtered by user. */
@Serializable
data class MaintenanceDto(
    @Serializable(with = LenientLongSerializer::class) val id: Long,
    val title: String = "",
    val description: String? = null,
    val strategy: String? = null,
    @Serializable(with = LenientBooleanSerializer::class) val active: Boolean = false,
    /** `"under-maintenance"`, `"scheduled"`, `"inactive"`, `"ended"`, `"unknown"`. */
    val status: String? = null,
    val timezone: String? = null,
) {
    val isUnderMaintenance: Boolean get() = status == STATUS_UNDER_MAINTENANCE

    companion object {
        const val STATUS_UNDER_MAINTENANCE = "under-maintenance"
    }
}

/** One entry of the `notificationList` event; provider settings are only in the JSON string [config]. */
@Serializable
data class NotificationDto(
    @Serializable(with = LenientLongSerializer::class) val id: Long,
    val name: String? = null,
    @Serializable(with = LenientBooleanSerializer::class) val active: Boolean = true,
    @Serializable(with = LenientBooleanSerializer::class) val isDefault: Boolean = false,
    @SerialName("is_default") @Serializable(with = LenientBooleanSerializer::class) val isDefaultRaw: Boolean = false,
    @Serializable(with = LenientNullableLongSerializer::class) val userId: Long? = null,
    val config: String? = null,
) {
    fun toModel(): KumaNotification = NotificationConfig.toModel(this)
}

/** A tag definition (`getTags`). */
@Serializable
data class TagDto(
    @Serializable(with = LenientLongSerializer::class) val id: Long,
    val name: String = "",
    val color: String = "",
) {
    fun toModel(): Tag = Tag(id = id, name = name, color = color)
}
