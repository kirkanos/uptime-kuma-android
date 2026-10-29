package net.kirkanos.uptimekuma.push.payload

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.kirkanos.uptimekuma.core.model.KumaNotification

/** Builds the Kuma `webhook` notification that posts schema-v1 JSON to this device's UnifiedPush endpoint. */
object KumaWebhookNotification {
    const val TYPE: String = "webhook"
    const val FIELD_URL: String = "webhookURL"
    const val FIELD_METHOD: String = "httpMethod"
    const val FIELD_CONTENT_TYPE: String = "webhookContentType"
    const val FIELD_CUSTOM_BODY: String = "webhookCustomBody"
    const val FIELD_HEADERS: String = "webhookAdditionalHeaders"

    /**
     * @param endpoint the UnifiedPush endpoint URL, verbatim (including `?up=1`).
     * @param ntfyToken access token of the ntfy user allowed to publish to `up*`.
     * @param deviceName shown in Kuma's notification list so the user can identify the device.
     * @param id existing Kuma notification id to update, `null` to create a new one.
     * @param isDefault Kuma's "Default enabled" flag: preselected for new monitors (web UI and app).
     */
    fun build(
        endpoint: String,
        ntfyToken: String,
        deviceName: String,
        id: Long? = null,
        isDefault: Boolean = false,
    ): KumaNotification =
        KumaNotification(
            id = id,
            name = notificationName(deviceName),
            type = TYPE,
            isDefault = isDefault,
            fields =
                mapOf(
                    FIELD_URL to endpoint,
                    FIELD_METHOD to "post",
                    FIELD_CONTENT_TYPE to "custom",
                    FIELD_CUSTOM_BODY to WebhookTemplate.BODY,
                    FIELD_HEADERS to headersJson(ntfyToken),
                ),
        )

    fun notificationName(deviceName: String): String = "${deviceName.ifBlank { "Android" }} (UnifiedPush)"

    /** Kuma expects the additional headers as a JSON *string*, which it `JSON.parse`s itself. */
    internal fun headersJson(ntfyToken: String): String =
        JsonObject(
            mapOf(
                "Authorization" to JsonPrimitive("Bearer ${ntfyToken.trim()}"),
                "Content-Type" to JsonPrimitive("application/json"),
            ),
        ).toString()
}
