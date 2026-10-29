package net.kirkanos.uptimekuma.feature.settings.server.common

import androidx.annotation.StringRes
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.CancellationException
import net.kirkanos.uptimekuma.core.model.KumaError
import net.kirkanos.uptimekuma.core.model.KumaException
import net.kirkanos.uptimekuma.core.ui.component.localizedMessage
import net.kirkanos.uptimekuma.feature.settings.R

/** Successful administration actions; shown with the app's own texts instead of Kuma's i18n keys. */
enum class AdminDone(
    @param:StringRes val textRes: Int,
) {
    SAVED(R.string.admin_done_saved),
    CREATED(R.string.admin_done_created),
    DELETED(R.string.admin_done_deleted),
    ENABLED(R.string.admin_done_enabled),
    DISABLED(R.string.admin_done_disabled),
    TEST_OK(R.string.admin_done_test_ok),
    PASSWORD_CHANGED(R.string.admin_done_password_changed),
    TWO_FACTOR_ENABLED(R.string.admin_done_2fa_enabled),
    TWO_FACTOR_DISABLED(R.string.admin_done_2fa_disabled),
    STATISTICS_CLEARED(R.string.admin_done_statistics_cleared),
    DATABASE_SHRUNK(R.string.admin_done_database_shrunk),
    AUTH_DISABLED(R.string.admin_done_auth_disabled),
    AUTH_ENABLED(R.string.admin_done_auth_enabled),
    TUNNEL_STARTING(R.string.admin_done_tunnel_starting),
    TUNNEL_STOPPING(R.string.admin_done_tunnel_stopping),
    TOKEN_REMOVED(R.string.admin_done_token_removed),
}

/** One-shot feedback of a ViewModel, consumed after it was shown. */
sealed interface AdminMessage {
    /** [detail] is server text worth showing (e.g. the container count of a Docker test). */
    data class Done(
        val what: AdminDone,
        val detail: String? = null,
    ) : AdminMessage

    data class Failed(
        val error: KumaError,
    ) : AdminMessage
}

/** Runs [block]; returns the error instead of throwing (cancellation still propagates). */
internal suspend fun <T> runKuma(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

/** The [KumaError] of a failed [runKuma] result. */
internal fun Throwable.toKumaError(): KumaError =
    (this as? KumaException)?.error ?: KumaError(KumaError.Kind.UNKNOWN, message)

/** Kuma's own (often i18n-key) rejection messages, mapped to the app's texts. */
@StringRes
internal fun kumaMessageRes(message: String?): Int? =
    when (message) {
        "passwordTooWeak" -> R.string.admin_error_password_too_weak
        "authInvalidToken" -> R.string.admin_error_invalid_token
        "2faAlreadyEnabled" -> R.string.admin_error_2fa_already_enabled
        "Incorrect current password" -> R.string.admin_error_wrong_password
        "Invalid new password" -> R.string.admin_error_password_too_weak
        else -> null
    }

/** Localized text for [error], preferring the mapping of Kuma's message. */
@Composable
internal fun adminErrorText(error: KumaError): String =
    kumaMessageRes(error.message)?.let { stringResource(it) } ?: error.localizedMessage()

/** Text of [message]: the app's success text (plus server detail) or the localized error. */
@Composable
internal fun adminMessageText(message: AdminMessage): String =
    when (message) {
        is AdminMessage.Failed -> {
            adminErrorText(message.error)
        }

        is AdminMessage.Done -> {
            val base = stringResource(message.what.textRes)
            message.detail?.takeIf { it.isNotBlank() }?.let {
                stringResource(
                    R.string.admin_done_with_detail,
                    base,
                    it,
                )
            }
                ?: base
        }
    }

/** Shows [message] once as a snackbar, then calls [onShown]. */
@Composable
internal fun AdminMessageSnackbar(
    message: AdminMessage?,
    hostState: SnackbarHostState,
    onShown: () -> Unit,
) {
    val text = message?.let { adminMessageText(it) }
    LaunchedEffect(message) {
        if (text != null) {
            onShown()
            hostState.showSnackbar(text)
        }
    }
}
