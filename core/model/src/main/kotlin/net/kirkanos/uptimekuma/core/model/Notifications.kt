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
    /** Package name of the chosen distributor (e.g. `io.heckel.ntfy`). */
    val distributor: String? = null,
)

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
