package net.kirkanos.uptimekuma.core.data.notification

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import net.kirkanos.uptimekuma.core.data.connection.KumaConnectionManager
import net.kirkanos.uptimekuma.core.domain.NotificationAdminRepository
import net.kirkanos.uptimekuma.core.kumaclient.NotificationConfig
import net.kirkanos.uptimekuma.core.kumaclient.json.asLenientBoolean
import net.kirkanos.uptimekuma.core.model.KumaNotification
import javax.inject.Inject
import javax.inject.Singleton

/** What [KumaNotificationAdminRepository] needs from the connection; a seam for unit tests. */
internal interface NotificationAdminBackend {
    /** `testNotification(payload)`; returns the ack message, throws `KumaException` for `ok:false`. */
    suspend fun testNotification(payload: JsonObject): String?

    /** Raw ack args of `checkApprise` (a bare boolean). */
    suspend fun checkApprise(): List<JsonElement>
}

internal class ConnectionNotificationAdminBackend
    @Inject
    constructor(
        private val manager: KumaConnectionManager,
    ) : NotificationAdminBackend {
        override suspend fun testNotification(payload: JsonObject): String? =
            manager.withApi { api -> api.testNotification(payload) }

        override suspend fun checkApprise(): List<JsonElement> =
            manager.withApi { api -> api.requestRaw("checkApprise", emptyList()) }
    }

@Singleton
internal class KumaNotificationAdminRepository
    @Inject
    constructor(
        private val backend: NotificationAdminBackend,
    ) : NotificationAdminRepository {
        // Same object as the web UI's NotificationDialog sends (and as KumaNotificationRepository.save).
        override suspend fun sendTest(notification: KumaNotification): String? =
            backend.testNotification(NotificationConfig.toPayload(notification))

        override suspend fun isAppriseAvailable(): Boolean =
            backend.checkApprise().firstOrNull().asLenientBoolean() == true
    }
