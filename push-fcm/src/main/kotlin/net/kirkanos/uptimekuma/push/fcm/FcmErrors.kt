package net.kirkanos.uptimekuma.push.fcm

import com.google.android.gms.common.ConnectionResult
import net.kirkanos.uptimekuma.push.data.PushSetupException
import net.kirkanos.uptimekuma.push.data.PushSetupException.Reason

/**
 * Maps Firebase / Play services failures to [PushSetupException] reasons the UI can explain.
 *
 * FCM reports token failures as `IOException`s whose message is a GMS error code (firebase-messaging 26.0.1,
 * `GmsRpc`: `SERVICE_NOT_AVAILABLE`, `INTERNAL_SERVER_ERROR`, `AUTHENTICATION_FAILED`, `INVALID_SENDER`,
 * `FIS_AUTH_ERROR`, `MISSING_INSTANCEID_SERVICE`, ...), usually wrapped in an `ExecutionException`. Firebase
 * Installations (20.0.0) rejects malformed values with `IllegalArgumentException("Please set a valid API key...")`
 * and records server-side 400/403 answers as `BAD CONFIG`. The whole cause chain is searched.
 */
internal object FcmErrors {
    private val CONFIG_MARKERS =
        listOf(
            "AUTHENTICATION_FAILED",
            "FIS_AUTH_ERROR",
            "INVALID_SENDER",
            "BAD CONFIG",
            "BAD_CONFIG",
            "API key",
            "API_KEY",
            "Please set",
            "PERMISSION_DENIED",
        )
    private val PLAY_SERVICES_MARKERS = listOf("MISSING_INSTANCEID_SERVICE")
    private val UNAVAILABLE_MARKERS =
        listOf(
            "SERVICE_NOT_AVAILABLE",
            "INTERNAL_SERVER_ERROR",
            "InternalServerError",
            "TIMEOUT",
            "Firebase Installations Service is unavailable",
            "UnknownHostException",
            "SocketTimeoutException",
            "ConnectException",
        )

    fun toSetupException(error: Throwable): PushSetupException {
        if (error is PushSetupException) return error
        val text =
            generateSequence(error) { it.cause }
                .take(MAX_CAUSES)
                .map { listOfNotNull(it.javaClass.simpleName, it.message).joinToString(": ") }
                .joinToString(" <- ")
        val reason =
            when {
                PLAY_SERVICES_MARKERS.any { text.contains(it) } -> Reason.PLAY_SERVICES_MISSING
                CONFIG_MARKERS.any { text.contains(it, ignoreCase = true) } -> Reason.FCM_CONFIG_REJECTED
                UNAVAILABLE_MARKERS.any { text.contains(it) } -> Reason.FCM_UNAVAILABLE
                else -> Reason.FCM_FAILED
            }
        return PushSetupException(reason, shortDetail(error))
    }

    /** Result of `GoogleApiAvailability.isGooglePlayServicesAvailable`; `null` = usable. */
    fun playServicesReason(connectionResult: Int): Reason? =
        when (connectionResult) {
            ConnectionResult.SUCCESS -> {
                null
            }

            ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED,
            ConnectionResult.SERVICE_UPDATING,
            ConnectionResult.SERVICE_DISABLED,
            -> {
                Reason.PLAY_SERVICES_UPDATE_REQUIRED
            }

            else -> {
                Reason.PLAY_SERVICES_MISSING
            }
        }

    /** The innermost message (the GMS error code), e.g. `SERVICE_NOT_AVAILABLE`. */
    private fun shortDetail(error: Throwable): String {
        val innermost = generateSequence(error) { it.cause }.take(MAX_CAUSES).last()
        return innermost.message?.takeIf { it.isNotBlank() } ?: innermost.javaClass.simpleName
    }

    private const val MAX_CAUSES = 8
}
