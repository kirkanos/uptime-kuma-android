package net.kirkanos.uptimekuma.core.model

/**
 * A notification provider configured in Kuma (`notificationList`). Provider-specific
 * fields are kept as a flat map of the JSON `config` (see `spec/notification-providers.json`).
 */
data class KumaNotification(
    val id: Long?,
    val name: String,
    /** Provider type id, e.g. `webhook`, `ntfy`, `telegram`. */
    val type: String,
    val isDefault: Boolean = false,
    val active: Boolean = true,
    /** Provider fields as JSON-compatible values (String, Number, Boolean, null, List, Map). */
    val fields: Map<String, Any?> = emptyMap(),
)

/** This device's push registration. Persisted by the push module. */
data class PushRegistration(
    /** UnifiedPush endpoint URL handed out by the distributor. */
    val endpoint: String,
    /** Id of the Kuma `webhook` notification created for this device, if set up. */
    val kumaNotificationId: Long? = null,
    /** Package name of the chosen distributor (e.g. `io.heckel.ntfy`); `null` for [PushTransport.FIREBASE]. */
    val distributor: String? = null,
    /** How alerts reach this device. Registrations stored before Firebase support are ntfy ones. */
    val transport: PushTransport = PushTransport.NTFY,
)

/** Push delivery path from Kuma to this device. */
enum class PushTransport {
    /** Kuma webhook -> self-hosted ntfy -> UnifiedPush distributor app (no Google services). */
    NTFY,

    /** Kuma webhook -> self-hosted relay -> Firebase Cloud Messaging of the user's own Firebase project. */
    FIREBASE,
}

/**
 * The four public values of a Firebase Android app (as in `google-services.json`). Nothing of this is compiled
 * into the app: every user creates an own Firebase project and enters or imports them.
 */
data class FirebaseConfig(
    /** `project_info.project_id`, e.g. `kuma-push-1a2b3`. */
    val projectId: String,
    /** `client_info.mobilesdk_app_id`, e.g. `1:123456789012:android:0123456789abcdef`. */
    val applicationId: String,
    /** `api_key[0].current_key`, e.g. `AIzaSy...` (39 characters). */
    val apiKey: String,
    /** `project_info.project_number` (the FCM sender id), e.g. `123456789012`. */
    val gcmSenderId: String,
) {
    /** Copy with surrounding whitespace removed from every value (pasted values often carry some). */
    fun trimmed(): FirebaseConfig =
        FirebaseConfig(projectId.trim(), applicationId.trim(), apiKey.trim(), gcmSenderId.trim())

    /** Fields whose format is invalid (after trimming); empty if the config looks usable. */
    fun invalidFields(): Set<FirebaseConfigField> {
        val c = trimmed()
        val invalid = mutableSetOf<FirebaseConfigField>()
        if (!PROJECT_ID.matches(c.projectId)) invalid += FirebaseConfigField.PROJECT_ID
        if (!SENDER_ID.matches(c.gcmSenderId)) invalid += FirebaseConfigField.SENDER_ID
        if (!API_KEY.matches(c.apiKey)) invalid += FirebaseConfigField.API_KEY
        val appId = APP_ID.matchEntire(c.applicationId)
        // The app id embeds the project number; a mismatch means values from two different projects.
        val senderMismatch = FirebaseConfigField.SENDER_ID !in invalid && appId?.groupValues?.get(1) != c.gcmSenderId
        if (appId == null || senderMismatch) invalid += FirebaseConfigField.APPLICATION_ID
        return invalid
    }

    private companion object {
        /** Google Cloud project ids: 6-30 characters, lowercase letters, digits and hyphens, starting with a letter. */
        val PROJECT_ID = Regex("[a-z][a-z0-9-]{4,28}[a-z0-9]")
        val SENDER_ID = Regex("[0-9]{1,20}")

        /** Same check as Firebase Installations (`Utils.API_KEY_FORMAT`). */
        val API_KEY = Regex("A[A-Za-z0-9_-]{38}")
        val APP_ID = Regex("1:([0-9]{1,20}):android:[0-9a-f]{1,64}")
    }
}

/** Fields of [FirebaseConfig], used to point at invalid input. */
enum class FirebaseConfigField { PROJECT_ID, APPLICATION_ID, API_KEY, SENDER_ID }

/** A parsed push message (payload schema v1, see docs/push/README.md). */
data class PushEvent(
    val monitorId: Long?,
    val monitorName: String?,
    val monitorType: String? = null,
    val target: String? = null,
    /** `null` for Kuma's "test notification". */
    val status: MonitorStatus?,
    val message: String,
    val timeMillis: Long? = null,
    val pingMs: Double? = null,
    val important: Boolean = true,
)
