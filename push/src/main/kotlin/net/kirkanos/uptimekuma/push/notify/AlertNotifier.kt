package net.kirkanos.uptimekuma.push.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import net.kirkanos.uptimekuma.core.model.AlertRecord
import net.kirkanos.uptimekuma.core.model.MonitorStatus
import net.kirkanos.uptimekuma.core.model.PushEvent
import net.kirkanos.uptimekuma.core.model.RepeatInterval
import net.kirkanos.uptimekuma.push.AlertPresenter
import net.kirkanos.uptimekuma.push.NotificationIntents
import net.kirkanos.uptimekuma.push.R
import net.kirkanos.uptimekuma.push.alerts.ActiveDownAlert
import net.kirkanos.uptimekuma.push.alerts.ActiveDownAlerts
import net.kirkanos.uptimekuma.push.alerts.AlertDecision
import net.kirkanos.uptimekuma.push.alerts.AlertGate
import net.kirkanos.uptimekuma.push.alerts.AlertHistoryStore
import net.kirkanos.uptimekuma.push.alerts.DataStoreAlertPreferencesRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns [PushEvent]s into system notifications.
 *
 * Every monitor owns one notification slot (tag `monitor:<id>`), so an UP replaces the DOWN of the same
 * monitor. All monitor alerts share one group with a summary. Missing POST_NOTIFICATIONS permission or
 * disabled notifications are tolerated silently.
 *
 * Every non-duplicate event is written to the in-app alert history ([AlertHistoryStore]); the user's
 * [AlertPreferences][net.kirkanos.uptimekuma.core.model.AlertPreferences] (mutes, quiet hours, UP / certificate
 * switches) decide via [AlertGate] whether it also becomes a system notification.
 *
 * A shown DOWN alert stays open in [ActiveDownAlerts] and rings again every
 * [repeatDown][net.kirkanos.uptimekuma.core.model.AlertPreferences.repeatDown] ([ReminderAlarm], [ReminderRunner])
 * until the monitor recovers or the user acknowledges it: with the notification's button, or by tapping it.
 */
@Singleton
class AlertNotifier
    @Inject
    internal constructor(
        @ApplicationContext private val context: Context,
        private val history: AlertHistory,
        private val preferences: DataStoreAlertPreferencesRepository,
        private val gate: AlertGate,
        private val alertLog: AlertHistoryStore,
        private val active: ActiveDownAlerts,
        private val alarm: ReminderAlarm,
    ) : AlertPresenter {
        private val manager = NotificationManagerCompat.from(context)

        /** Shows the event. Returns `false` if it was dropped (duplicate, suppressed) or could not be posted. */
        override fun show(event: PushEvent): Boolean {
            val monitorId = event.monitorId
            val status = event.status
            val downSince = monitorId?.let(history::downSince)
            if (!history.accept(monitorId, event.timeMillis, status)) return false
            val prefs = preferences.current()
            val decision = gate.decide(event, prefs)
            alertLog.record(event.toRecord(gate.nowMillis(), notified = decision == AlertDecision.SHOW))
            if (monitorId != null) trackOpenAlert(event, monitorId, decision, downSince)
            if (decision != AlertDecision.SHOW) {
                Log.i(LOG_TAG, "Alert not shown: $decision")
                // A silent recovery still resolves the monitor's DOWN notification.
                if (monitorId != null && status == MonitorStatus.UP) cancel(monitorId)
                return false
            }
            NotificationChannels.ensure(context)
            val notification =
                when {
                    monitorId == null -> {
                        buildGeneric(event)
                    }

                    status == null -> {
                        buildMonitorInfo(event, monitorId)
                    }

                    else -> {
                        buildMonitorAlert(
                            event,
                            monitorId,
                            status,
                            downSince,
                            prefs.repeatDown != RepeatInterval.OFF,
                        )
                    }
                }
            val tag = if (monitorId == null) TAG_GENERIC else monitorTag(monitorId, status)
            val posted = post(tag, NOTIFICATION_ID, notification)
            if (posted && monitorId != null) postSummary(notification.channelId)
            return posted
        }

        /** Removes the alert of a monitor, e.g. after the user opened it in the app. */
        fun cancel(monitorId: Long) {
            manager.cancel(monitorTag(monitorId, MonitorStatus.DOWN), NOTIFICATION_ID)
        }

        /**
         * Stops the reminders of the monitor's open DOWN alert. A notification that is still shown stays, silently,
         * marked as acknowledged. No-op if nothing is open. Called from the notification button and when the user
         * opens the monitor from the notification.
         */
        fun acknowledge(monitorId: Long) {
            val alert = active.update(monitorId) { it.copy(acknowledged = true) } ?: return
            alarm.reschedule()
            if (!isShown(monitorTag(monitorId, MonitorStatus.DOWN))) return
            val res = context.resources
            val notification =
                downBuilder(alert.toEvent(), monitorId, res.getString(R.string.push_alert_down_title, name(alert)))
                    .setSubText(res.getString(R.string.push_alert_acknowledged))
                    .setSilent(true)
                    .build()
            post(monitorTag(monitorId, MonitorStatus.DOWN), NOTIFICATION_ID, notification)
        }

        /** The monitor is no longer down although no UP push arrived: close its alert. */
        internal fun resolve(monitorId: Long) {
            active.remove(monitorId)
            cancel(monitorId)
        }

        /** Rings the open DOWN alert of [alert]'s monitor again. */
        internal fun remind(
            alert: ActiveDownAlert,
            nowMillis: Long,
        ) {
            val res = context.resources
            val since = alert.downSince
            val title =
                if (since != null && nowMillis > since) {
                    res.getString(
                        R.string.push_alert_still_down_title_duration,
                        name(alert),
                        formatDuration(
                            res,
                            nowMillis - since,
                        ),
                    )
                } else {
                    res.getString(R.string.push_alert_still_down_title, name(alert))
                }
            NotificationChannels.ensure(context)
            val notification =
                downBuilder(alert.toEvent(), alert.monitorId, title)
                    .addAction(acknowledgeAction(alert.monitorId))
                    .build()
            if (post(monitorTag(alert.monitorId, MonitorStatus.DOWN), NOTIFICATION_ID, notification)) {
                postSummary(notification.channelId)
            }
            active.update(alert.monitorId) { it.copy(lastAlertAt = nowMillis, reminders = it.reminders + 1) }
        }

        /** Opens or closes the monitor's entry in [ActiveDownAlerts] and moves the reminder alarm accordingly. */
        private fun trackOpenAlert(
            event: PushEvent,
            monitorId: Long,
            decision: AlertDecision,
            downSince: Long?,
        ) {
            val changed =
                when {
                    event.status == MonitorStatus.DOWN && decision == AlertDecision.SHOW -> {
                        active.put(ActiveDownAlert.of(event, monitorId, downSince, gate.nowMillis()))
                        true
                    }

                    // Any other heartbeat status ends the outage; a muted DOWN must not ring later.
                    event.status != null || decision == AlertDecision.MUTED -> {
                        active.remove(monitorId)
                    }

                    else -> {
                        false
                    }
                }
            if (changed) alarm.reschedule()
        }

        private fun name(alert: ActiveDownAlert): String =
            alert.monitorName ?: context.resources.getString(R.string.push_monitor_fallback_name, alert.monitorId)

        /** The DOWN notification without actions; the first alert and reminders add the acknowledge button. */
        private fun downBuilder(
            event: PushEvent,
            monitorId: Long,
            title: String,
        ): NotificationCompat.Builder =
            baseBuilder(NotificationChannels.MONITOR_DOWN, monitorId)
                .setContentTitle(title)
                .setContentText(event.message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(bigText(event)))
                .setSubText(event.target)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                .setColor(COLOR_DOWN)
                .setGroup(GROUP_MONITORS)
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
                .applyWhen(event.timeMillis)

        private fun acknowledgeAction(monitorId: Long): NotificationCompat.Action {
            val intent =
                Intent(context, AlertReminderReceiver::class.java)
                    .setAction(AlertReminderReceiver.ACTION_ACKNOWLEDGE)
                    .putExtra(AlertReminderReceiver.EXTRA_MONITOR_ID, monitorId)
            val pending =
                PendingIntent.getBroadcast(
                    context,
                    monitorId.hashCode(),
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            return NotificationCompat.Action
                .Builder(R.drawable.ic_stat_kuma, context.getString(R.string.push_action_acknowledge), pending)
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
                .setShowsUserInterface(false)
                .build()
        }

        private fun isShown(tag: String): Boolean =
            runCatching {
                context
                    .getSystemService(NotificationManager::class.java)
                    ?.activeNotifications
                    ?.any { it.tag == tag && it.id == NOTIFICATION_ID }
            }.getOrNull() ?: false

        fun canPostNotifications(): Boolean =
            manager.areNotificationsEnabled() && hasPostNotificationsPermission(context)

        private fun buildMonitorAlert(
            event: PushEvent,
            monitorId: Long,
            status: MonitorStatus,
            downSince: Long?,
            acknowledgeButton: Boolean,
        ): Notification {
            val res = context.resources
            val name = event.monitorName ?: res.getString(R.string.push_monitor_fallback_name, monitorId)
            val (channel, title) =
                when (status) {
                    MonitorStatus.DOWN -> {
                        NotificationChannels.MONITOR_DOWN to res.getString(R.string.push_alert_down_title, name)
                    }

                    MonitorStatus.UP -> {
                        val upAt = event.timeMillis
                        val downFor =
                            if (downSince != null && upAt != null && upAt > downSince) {
                                formatDuration(res, upAt - downSince)
                            } else {
                                null
                            }
                        NotificationChannels.MONITOR_UP to
                            if (downFor != null) {
                                res.getString(R.string.push_alert_up_title_duration, name, downFor)
                            } else {
                                res.getString(R.string.push_alert_up_title, name)
                            }
                    }

                    MonitorStatus.PENDING -> {
                        NotificationChannels.MONITOR_UP to res.getString(R.string.push_alert_pending_title, name)
                    }

                    MonitorStatus.MAINTENANCE -> {
                        NotificationChannels.MAINTENANCE to res.getString(R.string.push_alert_maintenance_title, name)
                    }
                }
            if (status == MonitorStatus.DOWN) {
                val builder = downBuilder(event, monitorId, title)
                if (acknowledgeButton) builder.addAction(acknowledgeAction(monitorId))
                return builder.build()
            }
            return baseBuilder(channel, monitorId)
                .setContentTitle(title)
                .setContentText(event.message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(bigText(event)))
                .setSubText(event.target)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setColor(COLOR_UP)
                .setGroup(GROUP_MONITORS)
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
                .applyWhen(event.timeMillis)
                .build()
        }

        /** Monitor-related message without heartbeat, e.g. Kuma's TLS certificate expiry warning. */
        private fun buildMonitorInfo(
            event: PushEvent,
            monitorId: Long,
        ): Notification {
            val name =
                event.monitorName ?: context.resources.getString(R.string.push_monitor_fallback_name, monitorId)
            return baseBuilder(NotificationChannels.CERT_EXPIRY, monitorId)
                .setContentTitle(name)
                .setContentText(event.message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(event.message))
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setColor(COLOR_WARNING)
                .setGroup(GROUP_MONITORS)
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
                .build()
        }

        /** Kuma's test notification, or a payload that could not be attributed to a monitor. */
        private fun buildGeneric(event: PushEvent): Notification {
            val res = context.resources
            val text = event.message.ifBlank { res.getString(R.string.push_generic_text) }
            return baseBuilder(NotificationChannels.TEST, null)
                .setContentTitle(res.getString(R.string.push_generic_title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .build()
        }

        private fun postSummary(channelId: String) {
            val active =
                runCatching {
                    context
                        .getSystemService(NotificationManager::class.java)
                        ?.activeNotifications
                        ?.count {
                            it.notification.group == GROUP_MONITORS &&
                                it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0
                        }
                }.getOrNull() ?: 1
            val res = context.resources
            val summary =
                baseBuilder(channelId, null)
                    .setContentTitle(res.getString(R.string.push_generic_title))
                    .setContentText(res.getQuantityString(R.plurals.push_summary_text, active, active))
                    .setGroup(GROUP_MONITORS)
                    .setGroupSummary(true)
                    .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
                    .setSilent(true)
                    .build()
            post(TAG_SUMMARY, NOTIFICATION_ID, summary)
        }

        private fun baseBuilder(
            channelId: String,
            monitorId: Long?,
        ): NotificationCompat.Builder =
            NotificationCompat
                .Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_stat_kuma)
                .setAutoCancel(true)
                .setOnlyAlertOnce(false)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setContentIntent(contentIntent(monitorId))

        private fun NotificationCompat.Builder.applyWhen(timeMillis: Long?): NotificationCompat.Builder =
            if (timeMillis != null) setWhen(timeMillis).setShowWhen(true) else this

        private fun bigText(event: PushEvent): String =
            listOfNotNull(event.message.takeIf { it.isNotBlank() }, event.target).joinToString("\n")

        /** Opens the app's launcher activity (no compile-time reference to :app) with the monitor id. */
        private fun contentIntent(monitorId: Long?): PendingIntent? {
            val launch: Intent =
                context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (monitorId != null) launch.putExtra(NotificationIntents.EXTRA_MONITOR_ID, monitorId)
            val requestCode = monitorId?.hashCode() ?: 0
            return PendingIntent.getActivity(
                context,
                requestCode,
                launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun post(
            tag: String,
            id: Int,
            notification: Notification,
        ): Boolean {
            if (!canPostNotifications()) {
                Log.i(LOG_TAG, "Notifications not permitted; alert dropped")
                return false
            }
            return try {
                manager.notify(tag, id, notification)
                true
            } catch (e: SecurityException) {
                Log.w(LOG_TAG, "Posting notification failed", e)
                false
            }
        }

        private fun monitorTag(
            monitorId: Long,
            status: MonitorStatus?,
        ): String = if (status == null) "monitor-info:$monitorId" else "monitor:$monitorId"

        internal companion object {
            const val NOTIFICATION_ID = 1
            const val GROUP_MONITORS = "net.kirkanos.uptimekuma.MONITOR_ALERTS"
            const val TAG_SUMMARY = "summary"
            const val TAG_GENERIC = "generic"
            const val LOG_TAG = "AlertNotifier"
            val COLOR_DOWN = 0xFFDC3545.toInt()
            val COLOR_UP = 0xFF1E8449.toInt()
            val COLOR_WARNING = 0xFFF0AD4E.toInt()

            fun formatDuration(
                res: Resources,
                millis: Long,
            ): String {
                val parts = DurationParts.of(millis)
                return when {
                    parts.days > 0 -> res.getString(R.string.push_duration_days_hours, parts.days, parts.hours)
                    parts.hours > 0 -> res.getString(R.string.push_duration_hours_minutes, parts.hours, parts.minutes)
                    parts.minutes > 0 -> res.getString(R.string.push_duration_minutes, parts.minutes)
                    else -> res.getString(R.string.push_duration_seconds, parts.seconds)
                }
            }
        }
    }

/** POST_NOTIFICATIONS is a runtime permission only from API 33; on 31/32 it is implicitly granted. */
fun hasPostNotificationsPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

/** Splits a duration for display ("2 h 5 min"). */
internal data class DurationParts(
    val days: Long,
    val hours: Long,
    val minutes: Long,
    val seconds: Long,
) {
    companion object {
        fun of(millis: Long): DurationParts {
            val totalSeconds = (millis / 1000).coerceAtLeast(0)
            return DurationParts(
                days = totalSeconds / 86_400,
                hours = totalSeconds % 86_400 / 3_600,
                minutes = totalSeconds % 3_600 / 60,
                seconds = totalSeconds % 60,
            )
        }
    }
}

/** History entry for this event, received at [nowMillis]. */
internal fun PushEvent.toRecord(
    nowMillis: Long,
    notified: Boolean,
): AlertRecord =
    AlertRecord(
        receivedAtMillis = nowMillis,
        monitorId = monitorId,
        monitorName = monitorName,
        status = status,
        message = message,
        eventTimeMillis = timeMillis,
        notified = notified,
    )
