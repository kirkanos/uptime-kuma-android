package net.kirkanos.uptimekuma.push.notify

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import net.kirkanos.uptimekuma.core.model.MonitorStatus
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remembers per monitor the time of the last alert and since when it is down.
 *
 * - Deduplication: ntfy replays cached messages after a reconnect (`since=<last id>`), and Kuma resends
 *   while a monitor stays down. An event whose heartbeat time is not newer than the last one is dropped.
 * - "Back up after 12 min": the DOWN time is kept until the matching UP arrives.
 *
 * Synchronous [SharedPreferences] on purpose: `onMessage` must post the notification without suspending.
 */
@Singleton
class AlertHistory internal constructor(
    private val prefs: SharedPreferences,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : this(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))

    /**
     * Records the event and returns `false` if it is a replay of (or older than) an already shown one.
     * Events without monitor id or time are always shown.
     */
    @Synchronized
    fun accept(
        monitorId: Long?,
        timeMillis: Long?,
        status: MonitorStatus?,
    ): Boolean {
        if (monitorId == null || timeMillis == null) return true
        val last = prefs.getLong(lastKey(monitorId), Long.MIN_VALUE)
        if (timeMillis <= last) return false
        prefs.edit {
            putLong(lastKey(monitorId), timeMillis)
            when (status) {
                MonitorStatus.DOWN -> if (!prefs.contains(downKey(monitorId))) putLong(downKey(monitorId), timeMillis)
                MonitorStatus.UP -> remove(downKey(monitorId))
                else -> Unit
            }
        }
        return true
    }

    /** When the monitor went down (heartbeat time of the first DOWN alert), or `null`. Read before [accept]. */
    fun downSince(monitorId: Long): Long? =
        prefs.getLong(downKey(monitorId), Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }

    fun clear() {
        prefs.edit { clear() }
    }

    private fun lastKey(id: Long) = "last_$id"

    private fun downKey(id: Long) = "down_$id"

    private companion object {
        const val PREFS_NAME = "push_alert_history"
    }
}
