package net.kirkanos.uptimekuma.push

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.kirkanos.uptimekuma.push.data.PushRepositoryImpl
import net.kirkanos.uptimekuma.push.di.PushScope
import net.kirkanos.uptimekuma.push.firebase.FirebaseSettingsStore
import net.kirkanos.uptimekuma.push.firebase.FirebaseTransport
import net.kirkanos.uptimekuma.push.notify.NotificationChannels
import net.kirkanos.uptimekuma.push.notify.ReminderAlarm
import java.util.Optional
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Call [initialize] from `Application.onCreate`: creates the notification channels, initializes Firebase with the
 * user's stored project values (`full` flavor only; must happen before `FirebaseMessagingService` is bound, which
 * Android does only after `Application.onCreate`) and, if ntfy push is set up, re-registers with the saved
 * distributor (idempotent; a changed endpoint is then pushed to Kuma). Also sets the reminder alarm of open DOWN
 * alerts again (lost when the app was force-stopped) and keeps it in line with the reminder setting.
 */
@Singleton
class PushInitializer
    @Inject
    internal constructor(
        @ApplicationContext private val context: Context,
        private val repository: PushRepositoryImpl,
        @PushScope private val scope: CoroutineScope,
        private val firebase: Optional<FirebaseTransport>,
        private val firebaseSettings: FirebaseSettingsStore,
        private val reminderAlarm: ReminderAlarm,
    ) {
        fun initialize() {
            NotificationChannels.ensure(context)
            initializeFirebase()
            scope.launch { repository.reRegisterIfConfigured() }
            scope.launch {
                reminderAlarm.reschedule()
                reminderAlarm.followSettings()
            }
        }

        /**
         * Synchronous on purpose (one small SharedPreferences read). Initializing Firebase without an active setup
         * is harmless: FCM auto-init stays off until a setup succeeded, so no token is created and nothing is sent.
         */
        private fun initializeFirebase() {
            val transport = firebase.orElse(null) ?: return
            val settings = firebaseSettings.load() ?: return
            if (settings.firebase.invalidFields().isNotEmpty()) return
            try {
                transport.initialize(settings.firebase)
            } catch (e: RuntimeException) {
                // Never let a broken stored config crash the app start; setup reports errors in the UI.
                Log.e(TAG, "Firebase initialization failed", e)
            }
        }

        private companion object {
            const val TAG = "PushInitializer"
        }
    }
