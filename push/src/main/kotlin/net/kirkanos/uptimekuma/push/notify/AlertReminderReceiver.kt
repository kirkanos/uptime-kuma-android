package net.kirkanos.uptimekuma.push.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.kirkanos.uptimekuma.push.di.PushScope

/**
 * Reminder alarm ([ACTION_REMIND]), the "Acknowledge" button of a DOWN notification ([ACTION_ACKNOWLEDGE]) and
 * reboot / app update, after which the alarm has to be set again. Not exported: only the system and this app send
 * to it.
 */
class AlertReminderReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val deps = EntryPointAccessors.fromApplication(context.applicationContext, Dependencies::class.java)
        val pending = goAsync()
        deps.scope().launch {
            try {
                when (intent.action) {
                    ACTION_REMIND -> {
                        deps.runner().run()
                    }

                    ACTION_ACKNOWLEDGE -> {
                        val id = intent.getLongExtra(EXTRA_MONITOR_ID, -1L)
                        if (id >= 0) deps.notifier().acknowledge(id)
                    }

                    else -> {
                        deps.alarm().reschedule()
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    internal interface Dependencies {
        @PushScope
        fun scope(): CoroutineScope

        fun runner(): ReminderRunner

        fun notifier(): AlertNotifier

        fun alarm(): ReminderAlarm
    }

    internal companion object {
        const val ACTION_REMIND = "net.kirkanos.uptimekuma.push.action.REMIND"
        const val ACTION_ACKNOWLEDGE = "net.kirkanos.uptimekuma.push.action.ACKNOWLEDGE"
        const val EXTRA_MONITOR_ID = "monitor_id"
    }
}
