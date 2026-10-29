package net.kirkanos.uptimekuma.push.firebase

import net.kirkanos.uptimekuma.core.model.FirebaseConfig

/**
 * Seam over the Firebase SDK, implemented in `:push-fcm` (only in the `full` flavor). :push compiles without any
 * Firebase or Google Play services dependency; it receives this interface through an optional Hilt binding
 * (`@BindsOptionalOf`), so `Optional.empty()` in `foss` builds and in JVM tests that do not provide a fake.
 */
interface FirebaseTransport {
    /**
     * Initializes the default `FirebaseApp` with [config] if it is not initialized yet. Synchronous and cheap;
     * called from `Application.onCreate` (before `FirebaseMessagingService` can be bound) and from the setup.
     */
    fun initialize(config: FirebaseConfig): FirebaseInitResult

    /**
     * Returns this device's FCM registration token. Checks Google Play services first.
     *
     * @throws net.kirkanos.uptimekuma.push.data.PushSetupException with one of the `PLAY_SERVICES_*` / `FCM_*`
     *   reasons.
     */
    suspend fun fetchToken(): String

    /** Deletes the FCM token (best effort, never throws) and disables FCM auto-init again. */
    suspend fun deleteToken()

    /**
     * FCM auto-init: token generation on app start and periodic token validation. Off by default (manifest);
     * enabled after a successful setup so that token rotations reach `onNewToken`.
     */
    fun setAutoInitEnabled(enabled: Boolean)
}

enum class FirebaseInitResult {
    /** The default app runs with exactly these values. */
    READY,

    /** The default app runs with other values; the SDK cannot re-initialize it in this process. */
    RESTART_REQUIRED,
}
