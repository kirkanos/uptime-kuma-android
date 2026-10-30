package net.kirkanos.uptimekuma.push.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import net.kirkanos.uptimekuma.push.alerts.ActiveDownAlerts
import net.kirkanos.uptimekuma.push.alerts.DataStoreAlertPreferencesRepository
import net.kirkanos.uptimekuma.push.alerts.ReminderPlan
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one alarm that wakes [AlertReminderReceiver] for the next due reminder of an open DOWN alert.
 *
 * Exact and allowed while idle, so a reminder rings on time and in Doze (where Android still spaces them at least
 * about 9 minutes apart). `USE_EXACT_ALARM` grants this from Android 13, `SCHEDULE_EXACT_ALARM` on 12. Should
 * exact alarms not be allowed, an inexact alarm is used: Android may then deliver it up to 75 % of the wait late.
 */
@Singleton
internal class ReminderAlarm
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val active: ActiveDownAlerts,
        private val preferences: DataStoreAlertPreferencesRepository,
    ) {
        fun reschedule() {
            val manager = context.getSystemService(AlarmManager::class.java) ?: return
            val at = ReminderPlan.nextAt(active.all(), preferences.current().repeatDown.millis)
            if (at == null) {
                manager.cancel(pendingIntent())
            } else if (manager.canScheduleExactAlarms()) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pendingIntent())
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pendingIntent())
            }
        }

        /** Reschedules whenever the user changes the interval. Runs until the scope ends. */
        suspend fun followSettings() {
            preferences
                .observePreferences()
                .map { it.repeatDown }
                .distinctUntilChanged()
                .drop(1)
                .collect { reschedule() }
        }

        private fun pendingIntent(): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                Intent(context, AlertReminderReceiver::class.java).setAction(AlertReminderReceiver.ACTION_REMIND),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        private companion object {
            const val REQUEST_CODE = 0x5245
        }
    }
