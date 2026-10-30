package net.kirkanos.uptimekuma.push.notify

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import net.kirkanos.uptimekuma.core.domain.MonitorRepository
import net.kirkanos.uptimekuma.core.domain.SessionRepository
import net.kirkanos.uptimekuma.core.model.DisplayStatus
import net.kirkanos.uptimekuma.push.alerts.ActiveDownAlerts
import net.kirkanos.uptimekuma.push.alerts.AlertGate
import net.kirkanos.uptimekuma.push.alerts.DataStoreAlertPreferencesRepository
import net.kirkanos.uptimekuma.push.alerts.ReminderPlan
import javax.inject.Inject

/**
 * Rings the due reminders of open DOWN alerts, started by [ReminderAlarm].
 *
 * Kuma sends DOWN once, so before ringing again the cache is synced: a monitor that is no longer down (the UP push
 * got lost, it was paused, deleted or put into maintenance) is resolved instead. If the sync fails (offline, server
 * unreachable) the reminder rings anyway: a needless reminder is better than a missed outage.
 */
internal class ReminderRunner
    @Inject
    constructor(
        private val active: ActiveDownAlerts,
        private val preferences: DataStoreAlertPreferencesRepository,
        private val gate: AlertGate,
        private val notifier: AlertNotifier,
        private val alarm: ReminderAlarm,
        private val session: SessionRepository,
        private val monitors: MonitorRepository,
    ) {
        suspend fun run() {
            val prefs = preferences.current()
            val now = gate.nowMillis()
            val due = ReminderPlan.due(active.all(), prefs.repeatDown.millis, now)
            if (due.isNotEmpty()) {
                if (session.observeServerConfig().first() == null) {
                    // Logged out: nothing is monitored any more.
                    active.all().forEach { notifier.resolve(it.monitorId) }
                    return
                }
                val stillDown = currentlyDown()
                Log.i(TAG, "${due.size} reminder(s) due; down after sync: ${stillDown ?: "sync failed"}")
                due.forEach { alert ->
                    when {
                        stillDown != null && alert.monitorId !in stillDown -> notifier.resolve(alert.monitorId)
                        prefs.activeMute(alert.monitorId, now) != null -> active.remove(alert.monitorId)
                        else -> notifier.remind(alert, gate.nowMillis())
                    }
                }
            }
            alarm.reschedule()
        }

        /** Ids of the monitors that are down according to a fresh sync, `null` if the sync failed. */
        private suspend fun currentlyDown(): Set<Long>? {
            val synced =
                try {
                    session.syncOnce(SYNC_TIMEOUT_MILLIS)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Sync before reminder failed", e)
                    false
                }
            if (!synced) return null
            return monitors
                .observeMonitors()
                .first()
                .filter { it.displayStatus == DisplayStatus.DOWN }
                .mapTo(HashSet()) { it.monitor.id }
        }

        private companion object {
            const val TAG = "ReminderRunner"

            /** Well inside the time a broadcast receiver may run after `goAsync`. */
            const val SYNC_TIMEOUT_MILLIS = 8_000L
        }
    }
