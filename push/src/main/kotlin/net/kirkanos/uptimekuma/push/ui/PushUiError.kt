package net.kirkanos.uptimekuma.push.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.res.stringResource
import net.kirkanos.uptimekuma.core.model.FirebaseConfigField
import net.kirkanos.uptimekuma.core.model.KumaError
import net.kirkanos.uptimekuma.core.model.KumaException
import net.kirkanos.uptimekuma.push.R
import net.kirkanos.uptimekuma.push.data.PushErrorCodes
import net.kirkanos.uptimekuma.push.data.PushSetupException

/** Errors of the push setup, mapped to localized strings in [text]. */
@Immutable
sealed interface PushUiError {
    data object NoDistributor : PushUiError

    data object MissingToken : PushUiError

    data object EndpointTimeout : PushUiError

    data object ActionRequired : PushUiError

    data object RegistrationFailed : PushUiError

    data object NotConfigured : PushUiError

    data object Network : PushUiError

    data object NotLoggedIn : PushUiError

    data object RateLimited : PushUiError

    data class ServerRejected(
        val detail: String?,
    ) : PushUiError

    data class Unknown(
        val detail: String?,
    ) : PushUiError

    // --- Firebase transport ----------------------------------------------------------------------

    data object FirebaseUnavailable : PushUiError

    data object RelayUrlInvalid : PushUiError

    data object MissingRelayToken : PushUiError

    /** [fields] empty = "some value is invalid" (code from a stored state without detail). */
    data class FirebaseConfigInvalid(
        val fields: Set<FirebaseConfigField>,
    ) : PushUiError

    data object PlayServicesMissing : PushUiError

    data object PlayServicesUpdateRequired : PushUiError

    data object FcmUnavailable : PushUiError

    data object FcmConfigRejected : PushUiError

    data object FcmTokenTimeout : PushUiError

    data class FcmFailed(
        val detail: String?,
    ) : PushUiError

    /** Firebase runs with other project values in this process; [text] asks for a restart. */
    data object RestartRequired : PushUiError

    /** The imported google-services.json has no Android app for [packageName]. */
    data class GoogleServicesNoMatch(
        val packageName: String,
        val found: List<String>,
    ) : PushUiError

    /** The imported file is no google-services.json, or [missing] is absent; `null` = unreadable. */
    data class GoogleServicesInvalid(
        val missing: String?,
    ) : PushUiError

    companion object {
        fun from(error: Throwable): PushUiError =
            when (error) {
                is PushSetupException -> from(error.reason, error.message)
                is KumaException -> from(error.error.kind, error.error.message)
                else -> Unknown(error.message)
            }

        /** From `PushSetupState.Error.message` (see [PushErrorCodes]). */
        fun fromCode(code: String): PushUiError {
            PushSetupException.Reason.entries
                .firstOrNull { it.name == code }
                ?.let { return from(it, null) }
            val kind = PushErrorCodes.kumaKind(code)?.let { k -> KumaError.Kind.entries.firstOrNull { it.name == k } }
            return if (kind != null) from(kind, null) else Unknown(null)
        }

        private fun from(
            reason: PushSetupException.Reason,
            detail: String?,
        ): PushUiError =
            when (reason) {
                PushSetupException.Reason.NO_DISTRIBUTOR -> NoDistributor
                PushSetupException.Reason.MISSING_TOKEN -> MissingToken
                PushSetupException.Reason.ENDPOINT_TIMEOUT -> EndpointTimeout
                PushSetupException.Reason.ACTION_REQUIRED -> ActionRequired
                PushSetupException.Reason.REGISTRATION_FAILED -> RegistrationFailed
                PushSetupException.Reason.NOT_CONFIGURED -> NotConfigured
                else -> fromFirebase(reason, detail)
            }

        private fun fromFirebase(
            reason: PushSetupException.Reason,
            detail: String?,
        ): PushUiError =
            when (reason) {
                PushSetupException.Reason.FIREBASE_UNAVAILABLE -> FirebaseUnavailable
                PushSetupException.Reason.RELAY_URL_INVALID -> RelayUrlInvalid
                PushSetupException.Reason.MISSING_RELAY_TOKEN -> MissingRelayToken
                PushSetupException.Reason.FIREBASE_CONFIG_INVALID -> FirebaseConfigInvalid(parseFields(detail))
                PushSetupException.Reason.PLAY_SERVICES_MISSING -> PlayServicesMissing
                PushSetupException.Reason.PLAY_SERVICES_UPDATE_REQUIRED -> PlayServicesUpdateRequired
                PushSetupException.Reason.FCM_UNAVAILABLE -> FcmUnavailable
                PushSetupException.Reason.FCM_CONFIG_REJECTED -> FcmConfigRejected
                PushSetupException.Reason.FCM_TOKEN_TIMEOUT -> FcmTokenTimeout
                PushSetupException.Reason.FCM_FAILED -> FcmFailed(detail?.takeIf { it != reason.name })
                PushSetupException.Reason.FIREBASE_RESTART_REQUIRED -> RestartRequired
                else -> Unknown(detail)
            }

        private fun parseFields(detail: String?): Set<FirebaseConfigField> =
            detail
                .orEmpty()
                .split(',')
                .mapNotNull { name -> FirebaseConfigField.entries.firstOrNull { it.name == name.trim() } }
                .toSet()

        private fun from(
            kind: KumaError.Kind,
            message: String?,
        ): PushUiError =
            when (kind) {
                KumaError.Kind.NETWORK, KumaError.Kind.TIMEOUT -> Network
                KumaError.Kind.UNAUTHORIZED, KumaError.Kind.INVALID_CREDENTIALS -> NotLoggedIn
                KumaError.Kind.RATE_LIMITED -> RateLimited
                KumaError.Kind.SERVER_REJECTED -> ServerRejected(message)
                else -> Unknown(message)
            }
    }
}

@Composable
fun PushUiError.text(): String =
    when (this) {
        PushUiError.NoDistributor -> stringResource(R.string.push_error_no_distributor)
        PushUiError.MissingToken -> stringResource(R.string.push_error_missing_token)
        PushUiError.EndpointTimeout -> stringResource(R.string.push_error_endpoint_timeout)
        PushUiError.ActionRequired -> stringResource(R.string.push_error_action_required)
        PushUiError.RegistrationFailed -> stringResource(R.string.push_error_registration_failed)
        PushUiError.NotConfigured -> stringResource(R.string.push_error_not_configured)
        PushUiError.Network -> stringResource(R.string.push_error_network)
        PushUiError.NotLoggedIn -> stringResource(R.string.push_error_not_logged_in)
        PushUiError.RateLimited -> stringResource(R.string.push_error_rate_limited)
        is PushUiError.ServerRejected -> detailed(R.string.push_error_server_rejected, detail)
        is PushUiError.Unknown -> detailed(R.string.push_error_unknown, detail)
        PushUiError.FirebaseUnavailable -> stringResource(R.string.push_error_firebase_unavailable)
        PushUiError.RelayUrlInvalid -> stringResource(R.string.push_error_relay_url_invalid)
        PushUiError.MissingRelayToken -> stringResource(R.string.push_error_missing_relay_token)
        is PushUiError.FirebaseConfigInvalid -> firebaseConfigInvalid(fields)
        PushUiError.PlayServicesMissing -> stringResource(R.string.push_error_play_services_missing)
        PushUiError.PlayServicesUpdateRequired -> stringResource(R.string.push_error_play_services_update)
        PushUiError.FcmUnavailable -> stringResource(R.string.push_error_fcm_unavailable)
        PushUiError.FcmConfigRejected -> stringResource(R.string.push_error_fcm_config_rejected)
        PushUiError.FcmTokenTimeout -> stringResource(R.string.push_error_fcm_timeout)
        is PushUiError.FcmFailed -> detailed(R.string.push_error_fcm_failed, detail)
        PushUiError.RestartRequired -> stringResource(R.string.push_error_restart_required)
        is PushUiError.GoogleServicesNoMatch -> googleServicesNoMatch(this)
        is PushUiError.GoogleServicesInvalid -> googleServicesInvalid(missing)
    }

@Composable
private fun firebaseConfigInvalid(fields: Set<FirebaseConfigField>): String =
    if (fields.isEmpty()) {
        stringResource(R.string.push_error_firebase_config_invalid_plain)
    } else {
        stringResource(
            R.string.push_error_firebase_config_invalid,
            fields.map { it.label() }.joinToString(", "),
        )
    }

@Composable
private fun googleServicesNoMatch(error: PushUiError.GoogleServicesNoMatch): String =
    if (error.found.isEmpty()) {
        stringResource(R.string.push_error_google_services_no_match_none, error.packageName)
    } else {
        stringResource(
            R.string.push_error_google_services_no_match,
            error.packageName,
            error.found.joinToString(", "),
        )
    }

@Composable
private fun googleServicesInvalid(missing: String?): String =
    if (missing == null) {
        stringResource(R.string.push_error_google_services_unreadable)
    } else {
        stringResource(R.string.push_error_google_services_invalid, missing)
    }

/** Label of the form field, as shown in the setup screen. */
@Composable
fun FirebaseConfigField.label(): String =
    when (this) {
        FirebaseConfigField.PROJECT_ID -> stringResource(R.string.push_firebase_project_id)
        FirebaseConfigField.APPLICATION_ID -> stringResource(R.string.push_firebase_app_id)
        FirebaseConfigField.API_KEY -> stringResource(R.string.push_firebase_api_key)
        FirebaseConfigField.SENDER_ID -> stringResource(R.string.push_firebase_sender_id)
    }

@Composable
private fun detailed(
    resId: Int,
    detail: String?,
): String =
    if (detail.isNullOrBlank()) {
        stringResource(R.string.push_error_unknown_plain)
    } else {
        stringResource(resId, detail)
    }
