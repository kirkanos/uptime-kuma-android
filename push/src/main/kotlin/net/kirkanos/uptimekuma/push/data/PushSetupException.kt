package net.kirkanos.uptimekuma.push.data

import net.kirkanos.uptimekuma.core.model.KumaException

/**
 * Failures of the push setup that are not Kuma errors. Kuma errors surface as [KumaException].
 * [PushSetupState.Error.message][net.kirkanos.uptimekuma.core.domain.PushSetupState.Error] carries
 * [errorCode] so the UI can localize it.
 */
class PushSetupException(
    val reason: Reason,
    detail: String? = null,
) : Exception(detail ?: reason.name) {
    enum class Reason {
        /** No UnifiedPush distributor (ntfy) installed. */
        NO_DISTRIBUTOR,

        /** The token field was empty. */
        MISSING_TOKEN,

        /** The distributor did not answer with an endpoint in time. */
        ENDPOINT_TIMEOUT,

        /** The distributor needs user interaction (e.g. UnifiedPush disabled in ntfy, no default server). */
        ACTION_REQUIRED,

        /** The distributor refused the registration. */
        REGISTRATION_FAILED,

        /** Push is not set up (test/update without registration or stored token). */
        NOT_CONFIGURED,

        // --- Firebase transport -------------------------------------------------------------------

        /** This build has no Firebase transport (`foss` flavor). */
        FIREBASE_UNAVAILABLE,

        /** Relay URL empty or not an http(s) URL with a host. */
        RELAY_URL_INVALID,

        /** The relay token field was empty. */
        MISSING_RELAY_TOKEN,

        /** One of the four Firebase values has an invalid format (detail: the field names). */
        FIREBASE_CONFIG_INVALID,

        /** Google Play services are missing, disabled or not usable on this device. */
        PLAY_SERVICES_MISSING,

        /** Google Play services must be updated (or enabled) first. */
        PLAY_SERVICES_UPDATE_REQUIRED,

        /** FCM could not be reached (`SERVICE_NOT_AVAILABLE`, offline, Google server error). */
        FCM_UNAVAILABLE,

        /** Firebase rejected the project values (invalid API key, app id, project or sender id). */
        FCM_CONFIG_REJECTED,

        /** No FCM token within the timeout. */
        FCM_TOKEN_TIMEOUT,

        /** Any other FCM token failure (detail: the SDK's error text). */
        FCM_FAILED,

        /**
         * Firebase is already initialized in this process with other project values; the SDK cannot switch
         * projects in-process. The new values are saved and take effect after an app restart.
         */
        FIREBASE_RESTART_REQUIRED,
    }
}

/** Stable, non-localized error codes used in `PushSetupState.Error.message`. */
object PushErrorCodes {
    private const val KUMA_PREFIX = "KUMA_"

    fun of(error: Throwable): String =
        when (error) {
            is PushSetupException -> error.reason.name
            is KumaException -> KUMA_PREFIX + error.error.kind.name
            else -> UNKNOWN
        }

    const val UNKNOWN: String = "UNKNOWN"

    /** Inverse of [of] for Kuma errors; `null` if [code] is not a Kuma error code. */
    fun kumaKind(code: String): String? = code.removePrefix(KUMA_PREFIX).takeIf { code.startsWith(KUMA_PREFIX) }
}
