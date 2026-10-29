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
import net.kirkanos.uptimekuma.push.AlertPresenter
import net.kirkanos.uptimekuma.push.NotificationIntents
import net.kirkanos.uptimekuma.push.R
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
 */
@Singleton
class AlertNotifier
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val history: AlertHistory,
        private val preferences: DataStoreAlertPreferencesRepository,
        private val gate: AlertGate,
        private val alertLog: AlertHistoryStore,
    ) : AlertPresenter {
        private val manager = NotificationManagerCompat.from(context)

        /** Shows the event. Returns `false` if it was dropped (duplicate, suppressed) or could not be posted. */
        override fun show(event: PushEvent): Boolean {
            val monitorId = event.monitorId
            val status = event.status
            val downSince = monitorId?.let(history::downSince)
            if (!history.accept(monitorId, event.timeMillis, status)) return false
            val decision = gate.decide(event, preferences.current())
            alertLog.record(event.toRecord(gate.nowMillis(), notified = decision == AlertDecision.SHOW))
            if (decision != AlertDecision.SHOW) {
                Log.i(LOG_TAG, "Alert not shown: $decision")
                // A silent recovery still resolves the monitor's DOWN notification.
                if (monitorId != null && status == MonitorStatus.UP) cancel(monitorId)
                return false
            }
            NotificationChannels.ensure(context)
            val notification =
                when {
                    monitorId == null -> buildGeneric(event)
                    status == null -> buildMonitorInfo(event, monitorId)
                    else -> buildMonitorAlert(event, monitorId, status, downSince)
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

        fun canPostNotifications(): Boolean =
            manager.areNotificationsEnabled() && hasPostNotificationsPermission(context)

        private fun buildMonitorAlert(
            event: PushEvent,
            monitorId: Long,
            status: MonitorStatus,
            downSince: Long?,
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
            val isDown = status == MonitorStatus.DOWN
            return baseBuilder(channel, monitorId)
                .setContentTitle(title)
                .setContentText(event.message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(bigText(event)))
                .setSubText(event.target)
                .setPriority(if (isDown) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(if (isDown) NotificationCompat.CATEGORY_ERROR else NotificationCompat.CATEGORY_STATUS)
                .setColor(if (isDown) COLOR_DOWN else COLOR_UP)
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
