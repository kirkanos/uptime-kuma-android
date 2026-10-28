package net.kirkanos.uptimekuma.push.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.annotation.StringRes
import net.kirkanos.uptimekuma.push.R

/** Notification channels of the app. Safe to call repeatedly (e.g. on app start and before posting). */
object NotificationChannels {
    const val MONITOR_DOWN: String = "monitor_down"
    const val MONITOR_UP: String = "monitor_up"
    const val CERT_EXPIRY: String = "cert_expiry"
    const val MAINTENANCE: String = "maintenance"
    const val TEST: String = "test"
    const val SYNC: String = "sync"

    private const val ALERT_RED: Int = 0xFFDC3545.toInt()
    private val DOWN_VIBRATION = longArrayOf(0, 400, 200, 400, 200, 800)

    fun ensure(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channels =
            listOf(
                downChannel(context),
                channel(
                    context,
                    MONITOR_UP,
                    R.string.push_channel_up_name,
                    R.string.push_channel_up_description,
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
                channel(
                    context,
                    CERT_EXPIRY,
                    R.string.push_channel_cert_name,
                    R.string.push_channel_cert_description,
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
                channel(
                    context,
                    MAINTENANCE,
                    R.string.push_channel_maintenance_name,
                    R.string.push_channel_maintenance_description,
                    NotificationManager.IMPORTANCE_LOW,
                ),
                channel(
                    context,
                    TEST,
                    R.string.push_channel_test_name,
                    R.string.push_channel_test_description,
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
                channel(
                    context,
                    SYNC,
                    R.string.push_channel_sync_name,
                    R.string.push_channel_sync_description,
                    NotificationManager.IMPORTANCE_MIN,
                ).apply { setShowBadge(false) },
            )
        manager.createNotificationChannels(channels)
    }

    private fun downChannel(context: Context): NotificationChannel =
        channel(
            context,
            MONITOR_DOWN,
            R.string.push_channel_down_name,
            R.string.push_channel_down_description,
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            enableVibration(true)
            vibrationPattern = DOWN_VIBRATION
            enableLights(true)
            lightColor = ALERT_RED
            // Only honoured once the user granted "Do Not Disturb" access to the app.
            setBypassDnd(true)
            val sound =
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            setSound(
                sound,
                AudioAttributes
                    .Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }

    private fun channel(
        context: Context,
        id: String,
        @StringRes name: Int,
        @StringRes description: Int,
        importance: Int,
    ): NotificationChannel =
        NotificationChannel(id, context.getString(name), importance).apply {
            this.description = context.getString(description)
        }
}
