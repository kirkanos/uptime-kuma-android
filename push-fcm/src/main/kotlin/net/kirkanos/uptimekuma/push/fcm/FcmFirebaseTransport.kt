package net.kirkanos.uptimekuma.push.fcm

import android.content.Context
import android.util.Log
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import net.kirkanos.uptimekuma.core.model.FirebaseConfig
import net.kirkanos.uptimekuma.push.data.PushSetupException
import net.kirkanos.uptimekuma.push.firebase.FirebaseInitResult
import net.kirkanos.uptimekuma.push.firebase.FirebaseTransport
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [FirebaseTransport] on top of the Firebase SDK (BoM 35.1.0: firebase-common 23.0.0, firebase-messaging 26.0.1).
 *
 * Runtime initialization, verified in the SDK sources:
 * - `FirebaseApp.initializeApp(context, options)` creates the default app and eagerly starts FCM
 *   (`FirebaseMessagingRegistrar` is `alwaysEager`). With `firebase_messaging_auto_init_enabled=false` in the
 *   manifest and no persisted override, that start does not create a token.
 * - A second `initializeApp` for the same name throws `IllegalStateException("FirebaseApp name [DEFAULT] already
 *   exists!")`. `FirebaseApp.delete()` exists but is marked `@hide`, and FCM keeps process-wide static state
 *   (`FirebaseMessaging.store`, executors), so switching projects in-process is not supported here:
 *   [initialize] reports [FirebaseInitResult.RESTART_REQUIRED] and the UI offers an app restart.
 * - `FirebaseMessaging.getInstance()` throws `IllegalStateException` while no default app exists.
 */
@Singleton
internal class FcmFirebaseTransport
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : FirebaseTransport {
        @Synchronized
        override fun initialize(config: FirebaseConfig): FirebaseInitResult {
            defaultApp()?.let { return if (it.options.matches(config)) FirebaseInitResult.READY else restart() }
            return try {
                FirebaseApp.initializeApp(context, config.toOptions())
                Log.i(TAG, "Firebase initialized for project ${config.projectId}")
                FirebaseInitResult.READY
            } catch (e: IllegalStateException) {
                // Initialized concurrently by someone else (should not happen without FirebaseInitProvider).
                Log.w(TAG, "Default FirebaseApp appeared during initialization", e)
                if (defaultApp()?.options?.matches(config) == true) FirebaseInitResult.READY else restart()
            }
        }

        override suspend fun fetchToken(): String {
            FcmErrors.playServicesReason(playServicesStatus())?.let { reason ->
                throw PushSetupException(reason, "Google Play services status ${playServicesStatus()}")
            }
            val messaging =
                messagingOrNull()
                    ?: throw PushSetupException(PushSetupException.Reason.FCM_FAILED, "Firebase not initialized")
            val token =
                try {
                    withTimeoutOrNull(TOKEN_TIMEOUT_MILLIS) { messaging.token.await() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    throw FcmErrors.toSetupException(e)
                } ?: throw PushSetupException(PushSetupException.Reason.FCM_TOKEN_TIMEOUT)
            if (token.isBlank()) throw PushSetupException(PushSetupException.Reason.FCM_FAILED, "empty token")
            return token
        }

        override suspend fun deleteToken() {
            val messaging = messagingOrNull() ?: return
            // Auto-init first, otherwise the SDK may create a new token right after the deletion.
            runCatching { messaging.isAutoInitEnabled = false }
            try {
                val done = withTimeoutOrNull(DELETE_TIMEOUT_MILLIS) { messaging.deleteToken().await() }
                if (done == null) Log.w(TAG, "Deleting the FCM token timed out")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Offline or Play services gone: the Kuma notification is deleted anyway, so nothing is sent.
                Log.w(TAG, "Deleting the FCM token failed", e)
            }
        }

        override fun setAutoInitEnabled(enabled: Boolean) {
            messagingOrNull()?.let { messaging ->
                runCatching { messaging.isAutoInitEnabled = enabled }
                    .onFailure { Log.w(TAG, "Could not change FCM auto-init", it) }
            }
        }

        private fun restart(): FirebaseInitResult {
            Log.i(TAG, "Firebase runs with other project values; a restart is required")
            return FirebaseInitResult.RESTART_REQUIRED
        }

        private fun defaultApp(): FirebaseApp? =
            FirebaseApp.getApps(context).firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }

        private fun messagingOrNull(): FirebaseMessaging? =
            if (defaultApp() == null) {
                null
            } else {
                try {
                    FirebaseMessaging.getInstance()
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "FirebaseMessaging unavailable", e)
                    null
                }
            }

        private fun playServicesStatus(): Int =
            GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)

        private companion object {
            const val TAG = "FcmTransport"
            const val TOKEN_TIMEOUT_MILLIS = 30_000L
            const val DELETE_TIMEOUT_MILLIS = 10_000L
        }
    }

internal fun FirebaseConfig.toOptions(): FirebaseOptions =
    FirebaseOptions
        .Builder()
        .setProjectId(projectId)
        .setApplicationId(applicationId)
        .setApiKey(apiKey)
        .setGcmSenderId(gcmSenderId)
        .build()

internal fun FirebaseOptions.matches(config: FirebaseConfig): Boolean =
    projectId == config.projectId &&
        applicationId == config.applicationId &&
        apiKey == config.apiKey &&
        gcmSenderId == config.gcmSenderId

/** Awaits a Play services [Task] without the kotlinx-coroutines-play-services dependency. */
private suspend fun <T> Task<T>.await(): T =
    suspendCancellableCoroutine { continuation ->
        addOnCompleteListener(Runnable::run) { task ->
            val error = task.exception
            when {
                error != null -> continuation.resumeWithException(error)
                task.isCanceled -> continuation.cancel()
                else -> continuation.resume(task.result)
            }
        }
    }
