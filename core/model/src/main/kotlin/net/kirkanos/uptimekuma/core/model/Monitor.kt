package net.kirkanos.uptimekuma.core.model

/**
 * A monitor as shown in lists and detail screens. Built from Kuma's `MonitorJSON`
 * (`monitorList` / `updateMonitorIntoList`). Editor-only fields live in [MonitorDraft].
 */
data class Monitor(
    val id: Long,
    val name: String,
    val type: String,
    val description: String? = null,
    /** URL for http-like types, otherwise `null`. */
    val url: String? = null,
    val hostname: String? = null,
    val port: Int? = null,
    /** Effective active flag (already combined with parents by Kuma). */
    val active: Boolean = true,
    /** True when a parent group is paused. */
    val forceInactive: Boolean = false,
    val parentId: Long? = null,
    val childrenIds: List<Long> = emptyList(),
    /** `"Parent / Child"` path as reported by Kuma. */
    val pathName: String = name,
    val intervalSeconds: Int = 60,
    val tags: List<MonitorTag> = emptyList(),
    val notificationIds: Set<Long> = emptySet(),
    /** True when a TLS expiry warning is enabled for this monitor. */
    val expiryNotification: Boolean = false,
    val weight: Int = 0,
) {
    val isGroup: Boolean get() = type == TYPE_GROUP

    /** Human-friendly target (URL, host:port or host). */
    val target: String?
        get() =
            url?.takeIf { it.isNotBlank() && it != "https://" }
                ?: hostname?.takeIf { it.isNotBlank() }?.let { h -> port?.let { "$h:$it" } ?: h }

    companion object {
        const val TYPE_GROUP = "group"
        const val TYPE_PUSH = "push"
    }
}

/** A tag attached to a monitor (`monitor_tag` row joined with `tag`). */
data class MonitorTag(
    val tagId: Long,
    val name: String,
    /** Hex color, e.g. `#2563EB`. */
    val color: String,
    val value: String = "",
)

/** A tag definition (`addTag` / `getTags`). */
data class Tag(
    val id: Long,
    val name: String,
    val color: String,
)

/** A single heartbeat. Times are UTC epoch millis (Kuma sends zone-less UTC strings). */
data class Heartbeat(
    val monitorId: Long,
    val status: MonitorStatus,
    val timeMillis: Long,
    val message: String = "",
    /** Response time in ms, `null` when not measured. */
    val pingMs: Double? = null,
    val important: Boolean = false,
    /** Seconds since the previous beat, if reported. */
    val durationSeconds: Int? = null,
    val retries: Int = 0,
)

/** Aggregated uptime figures from the `uptime` / `avgPing` events. Fractions are 0..1. */
data class UptimeStats(
    val monitorId: Long,
    val uptime24h: Double? = null,
    val uptime30d: Double? = null,
    val uptime1y: Double? = null,
    val avgPingMs: Double? = null,
)

/** TLS certificate details from the `certInfo` event (leaf certificate only). */
data class CertInfo(
    val monitorId: Long,
    val valid: Boolean,
    val subject: String? = null,
    val issuer: String? = null,
    val validFromMillis: Long? = null,
    val validToMillis: Long? = null,
    val daysRemaining: Int? = null,
    val fingerprint256: String? = null,
)

/** One point of `getMonitorChartData`. */
data class ChartPoint(
    val timeMillis: Long,
    val pingMs: Double?,
    val upCount: Int = 0,
    val downCount: Int = 0,
)

/** Everything a list row or detail header needs about one monitor. */
data class MonitorOverview(
    val monitor: Monitor,
    /** Status of the latest heartbeat, `null` when none was received yet. */
    val status: MonitorStatus?,
    /** Latest heartbeats, oldest first, at most ~100. */
    val recentBeats: List<Heartbeat> = emptyList(),
    val uptime: UptimeStats? = null,
    val cert: CertInfo? = null,
    val inMaintenance: Boolean = false,
) {
    val lastBeat: Heartbeat? get() = recentBeats.lastOrNull()

    /** Effective display status: paused wins, then maintenance, then the last heartbeat. */
    val displayStatus: DisplayStatus
        get() =
            when {
                !monitor.active -> DisplayStatus.PAUSED
                inMaintenance || status == MonitorStatus.MAINTENANCE -> DisplayStatus.MAINTENANCE
                status == MonitorStatus.DOWN -> DisplayStatus.DOWN
                status == MonitorStatus.PENDING -> DisplayStatus.PENDING
                status == MonitorStatus.UP -> DisplayStatus.UP
                else -> DisplayStatus.UNKNOWN
            }
}

/** Status as presented in the UI, including states that are not heartbeat codes. */
enum class DisplayStatus { UP, DOWN, PENDING, MAINTENANCE, PAUSED, UNKNOWN }
