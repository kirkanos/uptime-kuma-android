package net.kirkanos.uptimekuma.core.domain

import net.kirkanos.uptimekuma.core.model.KumaNotification

/**
 * Extra notification calls for the notification management screens, complementing
 * [KumaNotificationRepository] (list, save, delete). Suspend functions throw
 * net.kirkanos.uptimekuma.core.model.KumaException on failure (`ok:false` carries Kuma's message).
 */
interface NotificationAdminRepository {
    /**
     * `testNotification` with the given (possibly unsaved) configuration. Returns the provider's success
     * message as sent by Kuma (e.g. `"Sent Successfully."`), `null` if the ack had none.
     */
    suspend fun sendTest(notification: KumaNotification): String?

    /**
     * `checkApprise`: whether the `apprise` CLI exists on the server. Also makes sure a connection exists,
     * so the cached `notificationList` gets (re)filled.
     */
    suspend fun isAppriseAvailable(): Boolean
}
