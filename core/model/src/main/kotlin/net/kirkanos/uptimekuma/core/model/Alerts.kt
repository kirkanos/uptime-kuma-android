package net.kirkanos.uptimekuma.core.model

/** User preferences that decide which push alerts become system notifications. Persisted by :push. */
data class AlertPreferences(
    /** Notify when a monitor recovers (UP after DOWN). */
    val notifyOnUp: Boolean = true,
    /** Notify about Kuma's TLS certificate expiry warnings. */
    val notifyOnCertExpiry: Boolean = true,
    val quietHours: QuietHours = QuietHours(),
    /** Active and expired mutes by monitor id; check [MonitorMute.isActive]. */
    val mutes: Map<Long, MonitorMute> = emptyMap(),
) {
    /** The mute of [monitorId] if it is in effect at [nowMillis]. */
    fun activeMute(
        monitorId: Long,
        nowMillis: Long,
    ): MonitorMute? = mutes[monitorId]?.takeIf { it.isActive(nowMillis) }
}

/**
 * Daily window (device time zone) during which only DOWN alerts of non-muted monitors are shown.
 * Times are minutes after midnight; a window with `start > end` spans midnight, `start == end` is empty.
 */
data class QuietHours(
    val enabled: Boolean = false,
    val startMinuteOfDay: Int = DEFAULT_START,
    val endMinuteOfDay: Int = DEFAULT_END,
) {
    /** True when [minuteOfDay] (0..1439) lies inside the window; ignores [enabled]. */
    fun contains(minuteOfDay: Int): Boolean =
        when {
            startMinuteOfDay == endMinuteOfDay -> false
            startMinuteOfDay < endMinuteOfDay -> minuteOfDay in startMinuteOfDay until endMinuteOfDay
            else -> minuteOfDay >= startMinuteOfDay || minuteOfDay < endMinuteOfDay
        }

    companion object {
        const val MINUTES_PER_DAY: Int = 24 * 60
        const val DEFAULT_START: Int = 22 * 60
        const val DEFAULT_END: Int = 7 * 60
    }
}

/** Notifications of one monitor are suppressed until [untilMillis] (`null` = until unmuted). */
data class MonitorMute(
    val monitorId: Long,
    val untilMillis: Long?,
) {
    fun isActive(nowMillis: Long): Boolean = untilMillis == null || nowMillis < untilMillis
}

/** Mute durations offered in the UI. [millis] `null` = until unmuted. */
enum class MuteDuration(
    val millis: Long?,
) {
    ONE_HOUR(HOUR),
    EIGHT_HOURS(8 * HOUR),
    ONE_DAY(24 * HOUR),
    UNTIL_UNMUTED(null),
    ;

    /** End of a mute started at [nowMillis], `null` for [UNTIL_UNMUTED]. */
    fun untilMillis(nowMillis: Long): Long? = millis?.let { nowMillis + it }
}

private const val HOUR = 3_600_000L

/** One received push alert, kept in the in-app alert history (newest first, bounded). */
data class AlertRecord(
    /** When the alert arrived on the device. */
    val receivedAtMillis: Long,
    val monitorId: Long?,
    val monitorName: String?,
    /** `null` for messages without heartbeat (test notification, certificate expiry). */
    val status: MonitorStatus?,
    val message: String,
    /** Heartbeat time reported by Kuma, if any. */
    val eventTimeMillis: Long? = null,
    /** False when the alert was recorded but not shown (muted, quiet hours, disabled type). */
    val notified: Boolean = true,
)
