package net.kirkanos.uptimekuma.core.testing

import net.kirkanos.uptimekuma.core.domain.NotificationAdminRepository
import net.kirkanos.uptimekuma.core.model.KumaException
import net.kirkanos.uptimekuma.core.model.KumaNotification

/** In-memory [NotificationAdminRepository]; records tested configurations. */
class FakeNotificationAdminRepository(
    var testMessage: String? = "Sent Successfully.",
    var apprise: Boolean = true,
) : NotificationAdminRepository {
    /** Thrown by [sendTest] when set. */
    var testError: KumaException? = null

    /** Thrown by [isAppriseAvailable] when set. */
    var connectError: KumaException? = null
    val tested = mutableListOf<KumaNotification>()
    var appriseChecks = 0
        private set

    override suspend fun sendTest(notification: KumaNotification): String? {
        tested += notification
        testError?.let { throw it }
        return testMessage
    }

    override suspend fun isAppriseAvailable(): Boolean {
        appriseChecks++
        connectError?.let { throw it }
        return apprise
    }
}
