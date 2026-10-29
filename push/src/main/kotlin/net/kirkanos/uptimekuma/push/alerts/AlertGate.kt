package net.kirkanos.uptimekuma.push.alerts

import net.kirkanos.uptimekuma.core.model.AlertPreferences
import net.kirkanos.uptimekuma.core.model.MonitorStatus
import net.kirkanos.uptimekuma.core.model.PushEvent
import java.time.Clock
import java.time.Instant
import javax.inject.Inject

/** Why an alert was or was not turned into a notification. */
enum class AlertDecision {
    SHOW,
    MUTED,
    UP_DISABLED,
    CERT_EXPIRY_DISABLED,
    QUIET_HOURS,
}

/**
 * Applies the [AlertPreferences] to an incoming [PushEvent]:
 * - test / unattributed messages are always shown,
 * - a muted monitor never alerts (the cache is synced anyway),
 * - recoveries (UP) and certificate warnings can be switched off,
 * - during quiet hours only DOWN alerts get through.
 *
 * Time and time zone come from [clock].
 */
class AlertGate internal constructor(
    private val clock: Clock,
) {
    @Inject
    constructor() : this(Clock.systemDefaultZone())

    fun nowMillis(): Long = clock.millis()

    fun decide(
        event: PushEvent,
        preferences: AlertPreferences,
    ): AlertDecision {
        val monitorId = event.monitorId ?: return AlertDecision.SHOW
        val now = clock.millis()
        val status = event.status
        return when {
            preferences.activeMute(monitorId, now) != null -> {
                AlertDecision.MUTED
            }

            status == null && !preferences.notifyOnCertExpiry -> {
                AlertDecision.CERT_EXPIRY_DISABLED
            }

            status == MonitorStatus.UP && !preferences.notifyOnUp -> {
                AlertDecision.UP_DISABLED
            }

            status != MonitorStatus.DOWN && preferences.quietHours.enabled && inQuietHours(preferences, now) -> {
                AlertDecision.QUIET_HOURS
            }

            else -> {
                AlertDecision.SHOW
            }
        }
    }

    private fun inQuietHours(
        preferences: AlertPreferences,
        nowMillis: Long,
    ): Boolean {
        val local = Instant.ofEpochMilli(nowMillis).atZone(clock.zone).toLocalTime()
        return preferences.quietHours.contains(local.hour * MINUTES_PER_HOUR + local.minute)
    }

    private companion object {
        const val MINUTES_PER_HOUR = 60
    }
}
