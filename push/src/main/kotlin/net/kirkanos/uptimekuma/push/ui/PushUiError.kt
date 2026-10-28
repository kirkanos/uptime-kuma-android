package net.kirkanos.uptimekuma.push.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.res.stringResource
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

    companion object {
        fun from(error: Throwable): PushUiError =
            when (error) {
                is PushSetupException -> from(error.reason)
                is KumaException -> from(error.error.kind, error.error.message)
                else -> Unknown(error.message)
            }

        /** From `PushSetupState.Error.message` (see [PushErrorCodes]). */
        fun fromCode(code: String): PushUiError {
            PushSetupException.Reason.entries
                .firstOrNull { it.name == code }
                ?.let { return from(it) }
            val kind = PushErrorCodes.kumaKind(code)?.let { k -> KumaError.Kind.entries.firstOrNull { it.name == k } }
            return if (kind != null) from(kind, null) else Unknown(null)
        }

        private fun from(reason: PushSetupException.Reason): PushUiError =
            when (reason) {
                PushSetupException.Reason.NO_DISTRIBUTOR -> NoDistributor
                PushSetupException.Reason.MISSING_TOKEN -> MissingToken
                PushSetupException.Reason.ENDPOINT_TIMEOUT -> EndpointTimeout
                PushSetupException.Reason.ACTION_REQUIRED -> ActionRequired
                PushSetupException.Reason.REGISTRATION_FAILED -> RegistrationFailed
                PushSetupException.Reason.NOT_CONFIGURED -> NotConfigured
            }

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
