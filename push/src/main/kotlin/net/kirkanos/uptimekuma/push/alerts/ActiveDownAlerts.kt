package net.kirkanos.uptimekuma.push.alerts

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import net.kirkanos.uptimekuma.core.model.MonitorStatus
import net.kirkanos.uptimekuma.core.model.PushEvent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A DOWN alert that is still open: shown, the monitor has not recovered yet. It rings again every
 * [RepeatInterval][net.kirkanos.uptimekuma.core.model.RepeatInterval] until it is [acknowledged] or resolved.
 */
@Serializable
internal data class ActiveDownAlert(
    val monitorId: Long,
    val monitorName: String? = null,
    val monitorType: String? = null,
    val target: String? = null,
    val message: String = "",
    /** Heartbeat time of the DOWN event. */
    val eventTime: Long? = null,
    /** When the monitor went down (first DOWN heartbeat), for "down for 25 min". */
    val downSince: Long? = null,
    /** Device time of the last time this alert rang (first alert or reminder). */
    val lastAlertAt: Long,
    val reminders: Int = 0,
    val acknowledged: Boolean = false,
) {
    fun toEvent(): PushEvent =
        PushEvent(
            monitorId = monitorId,
            monitorName = monitorName,
            monitorType = monitorType,
            target = target,
            status = MonitorStatus.DOWN,
            message = message,
            timeMillis = eventTime,
        )

    companion object {
        fun of(
            event: PushEvent,
            monitorId: Long,
            downSince: Long?,
            nowMillis: Long,
        ): ActiveDownAlert =
            ActiveDownAlert(
                monitorId = monitorId,
                monitorName = event.monitorName,
                monitorType = event.monitorType,
                target = event.target,
                message = event.message,
                eventTime = event.timeMillis,
                downSince = downSince ?: event.timeMillis,
                lastAlertAt = nowMillis,
            )
    }
}

/** When reminders ring; pure so it can be tested without Android. */
internal object ReminderPlan {
    /** Reminders this early are rung now instead of scheduling an alarm a few seconds later. */
    const val SLACK_MILLIS: Long = 60_000

    /** Unacknowledged alerts whose next reminder is due at [nowMillis]. */
    fun due(
        alerts: Collection<ActiveDownAlert>,
        intervalMillis: Long?,
        nowMillis: Long,
    ): List<ActiveDownAlert> {
        if (intervalMillis == null) return emptyList()
        return alerts.filter { !it.acknowledged && it.lastAlertAt + intervalMillis <= nowMillis + SLACK_MILLIS }
    }

    /** Time of the next reminder, `null` if nothing is left to remind of. */
    fun nextAt(
        alerts: Collection<ActiveDownAlert>,
        intervalMillis: Long?,
    ): Long? {
        if (intervalMillis == null) return null
        return alerts.filter { !it.acknowledged }.minOfOrNull { it.lastAlertAt + intervalMillis }
    }
}

/**
 * Open DOWN alerts by monitor id. Synchronous [SharedPreferences] (one JSON entry per monitor) like
 * [AlertHistory][net.kirkanos.uptimekuma.push.notify.AlertHistory]: written while a push is handled, without
 * suspending.
 */
@Singleton
internal class ActiveDownAlerts(
    private val prefs: SharedPreferences,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : this(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))

    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun get(monitorId: Long): ActiveDownAlert? = prefs.getString(key(monitorId), null)?.let(::decode)

    @Synchronized
    fun all(): List<ActiveDownAlert> = prefs.all.values.mapNotNull { (it as? String)?.let(::decode) }

    @Synchronized
    fun put(alert: ActiveDownAlert) {
        prefs.edit { putString(key(alert.monitorId), json.encodeToString(ActiveDownAlert.serializer(), alert)) }
    }

    /** Applies [transform] to the stored alert; returns the new value, `null` if there was none. */
    @Synchronized
    fun update(
        monitorId: Long,
        transform: (ActiveDownAlert) -> ActiveDownAlert,
    ): ActiveDownAlert? = get(monitorId)?.let(transform)?.also(::put)

    /** Returns `true` if an alert was removed. */
    @Synchronized
    fun remove(monitorId: Long): Boolean {
        if (!prefs.contains(key(monitorId))) return false
        prefs.edit { remove(key(monitorId)) }
        return true
    }

    @Synchronized
    fun clear() {
        prefs.edit { clear() }
    }

    private fun decode(raw: String): ActiveDownAlert? =
        try {
            json.decodeFromString(ActiveDownAlert.serializer(), raw)
        } catch (e: SerializationException) {
            Log.w(TAG, "Dropping unreadable active alert", e)
            null
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Dropping unreadable active alert", e)
            null
        }

    private fun key(monitorId: Long) = "monitor_$monitorId"

    private companion object {
        const val PREFS_NAME = "push_active_down_alerts"
        const val TAG = "ActiveDownAlerts"
    }
}
