package net.kirkanos.uptimekuma.push.payload

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.kirkanos.uptimekuma.core.model.KumaNotification
import net.kirkanos.uptimekuma.core.model.PushTransport

/**
 * Builds the Kuma `webhook` notification that posts schema-v1 JSON ([WebhookTemplate]) to this device's push
 * endpoint: the UnifiedPush endpoint ([build]) or the FCM relay ([buildRelay]). Both variants send the very same
 * body and a bearer token, so the app parses what arrives with one parser.
 */
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
    ): KumaNotification = notification(endpoint, ntfyToken, notificationName(deviceName), id, isDefault)

    /**
     * Variant for the Firebase transport.
     *
     * @param webhookUrl `<relay>/v1/fcm/<FCM token>` (see `RelayUrl.webhookUrl`).
     * @param relayToken the relay's `RELAY_TOKEN`, sent as bearer token.
     */
    fun buildRelay(
        webhookUrl: String,
        relayToken: String,
        deviceName: String,
        id: Long? = null,
        isDefault: Boolean = false,
    ): KumaNotification = notification(webhookUrl, relayToken, relayNotificationName(deviceName), id, isDefault)

    /** Dispatches on [transport]; the URL and token are the transport-specific ones. */
    fun build(
        transport: PushTransport,
        url: String,
        token: String,
        deviceName: String,
        id: Long?,
        isDefault: Boolean,
    ): KumaNotification =
        when (transport) {
            PushTransport.NTFY -> build(url, token, deviceName, id, isDefault)
            PushTransport.FIREBASE -> buildRelay(url, token, deviceName, id, isDefault)
        }

    fun notificationName(deviceName: String): String = "${deviceName.ifBlank { "Android" }} (UnifiedPush)"

    fun relayNotificationName(deviceName: String): String = "${deviceName.ifBlank { "Android" }} (Firebase)"

    private fun notification(
        url: String,
        token: String,
        name: String,
        id: Long?,
        isDefault: Boolean,
    ) = KumaNotification(
        id = id,
        name = name,
        type = TYPE,
        isDefault = isDefault,
        fields =
            mapOf(
                FIELD_URL to url,
                FIELD_METHOD to "post",
                FIELD_CONTENT_TYPE to "custom",
                FIELD_CUSTOM_BODY to WebhookTemplate.BODY,
                FIELD_HEADERS to headersJson(token),
            ),
    )

    /** Kuma expects the additional headers as a JSON *string*, which it `JSON.parse`s itself. */
    internal fun headersJson(token: String): String =
        JsonObject(
            mapOf(
                "Authorization" to JsonPrimitive("Bearer ${token.trim()}"),
                "Content-Type" to JsonPrimitive("application/json"),
            ),
        ).toString()
}
