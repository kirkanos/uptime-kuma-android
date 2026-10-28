package net.kirkanos.uptimekuma.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.kirkanos.uptimekuma.core.domain.SessionRepository
import net.kirkanos.uptimekuma.core.model.KumaError
import net.kirkanos.uptimekuma.core.model.KumaException
import net.kirkanos.uptimekuma.core.model.LoginResult
import javax.inject.Inject

enum class LoginStep { SERVER, CREDENTIALS, TWO_FACTOR, DONE }

data class LoginUiState(
    val step: LoginStep = LoginStep.SERVER,
    val serverUrl: String = "",
    val urlError: ServerUrl.Reason? = null,
    /** Normalised URL accepted on the server step. */
    val baseUrl: String? = null,
    val insecure: Boolean = false,
    val username: String = "",
    val password: String = "",
    val totp: String = "",
    val busy: Boolean = false,
    val error: KumaError? = null,
    /** True when a server is already configured and only the session must be renewed. */
    val reLogin: Boolean = false,
) {
    val canSubmitCredentials: Boolean get() = !busy && username.isNotBlank() && password.isNotEmpty()
    val canSubmitTotp: Boolean get() = !busy && totp.length == TOTP_LENGTH

    companion object {
        const val TOTP_LENGTH = 6
    }
}

/**
 * Onboarding / re-login flow: server URL -> credentials -> optional 2FA code -> done.
 * When a server is already configured (session expired), starts at the credentials step with
 * URL and username pre-filled.
 */
@HiltViewModel
class LoginViewModel
    @Inject
    constructor(
        private val sessionRepository: SessionRepository,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow(LoginUiState())
        val state: StateFlow<LoginUiState> = mutableState.asStateFlow()

        init {
            viewModelScope.launch {
                val config = sessionRepository.observeServerConfig().first() ?: return@launch
                mutableState.update {
                    if (it.step != LoginStep.SERVER || it.serverUrl.isNotEmpty()) return@update it
                    it.copy(
                        step = LoginStep.CREDENTIALS,
                        serverUrl = config.baseUrl,
                        baseUrl = config.baseUrl,
                        insecure = config.baseUrl.startsWith("http://"),
                        username = config.username,
                        reLogin = true,
                    )
                }
            }
        }

        fun onServerUrlChange(value: String) {
            mutableState.update { it.copy(serverUrl = value, urlError = null, error = null) }
        }

        fun submitServerUrl() {
            when (val result = ServerUrl.normalize(state.value.serverUrl)) {
                is ServerUrl.Result.Invalid -> {
                    mutableState.update { it.copy(urlError = result.reason) }
                }

                is ServerUrl.Result.Valid -> {
                    mutableState.update {
                        it.copy(
                            step = LoginStep.CREDENTIALS,
                            serverUrl = result.url,
                            baseUrl = result.url,
                            insecure = result.insecure,
                            urlError = null,
                            error = null,
                        )
                    }
                }
            }
        }

        fun onUsernameChange(value: String) {
            mutableState.update { it.copy(username = value, error = null) }
        }

        fun onPasswordChange(value: String) {
            mutableState.update { it.copy(password = value, error = null) }
        }

        fun onTotpChange(value: String) {
            val digits = value.filter(Char::isDigit).take(LoginUiState.TOTP_LENGTH)
            mutableState.update { it.copy(totp = digits, error = null) }
        }

        fun submitCredentials() {
            if (state.value.canSubmitCredentials) login(totp = null)
        }

        fun submitTotp() {
            if (state.value.canSubmitTotp) login(totp = state.value.totp)
        }

        /** Goes one step back. Returns false when already on the first step (let the system handle back). */
        fun back(): Boolean {
            val current = state.value
            val previous =
                when {
                    current.busy -> current.step
                    current.step == LoginStep.TWO_FACTOR -> LoginStep.CREDENTIALS
                    current.step == LoginStep.CREDENTIALS && !current.reLogin -> LoginStep.SERVER
                    else -> null
                }
            if (previous != null && previous != current.step) {
                mutableState.update { it.copy(step = previous, error = null, totp = "") }
            }
            return previous != null
        }

        private fun login(totp: String?) {
            val current = state.value
            val baseUrl = current.baseUrl ?: return
            mutableState.update { it.copy(busy = true, error = null) }
            viewModelScope.launch {
                val result =
                    try {
                        sessionRepository.login(baseUrl, current.username.trim(), current.password, totp)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: KumaException) {
                        LoginResult.Failure(e.error)
                    } catch (e: Exception) {
                        LoginResult.Failure(KumaError(KumaError.Kind.UNKNOWN, e.message))
                    }
                mutableState.update { it.afterLogin(result, totpSent = totp != null) }
            }
        }

        private fun LoginUiState.afterLogin(
            result: LoginResult,
            totpSent: Boolean,
        ): LoginUiState =
            when (result) {
                LoginResult.Success -> {
                    copy(busy = false, step = LoginStep.DONE, password = "", totp = "")
                }

                LoginResult.TwoFactorRequired -> {
                    if (totpSent) {
                        copy(busy = false, totp = "", error = KumaError(KumaError.Kind.INVALID_CREDENTIALS))
                    } else {
                        copy(busy = false, step = LoginStep.TWO_FACTOR)
                    }
                }

                is LoginResult.Failure -> {
                    copy(busy = false, error = result.error, totp = if (totpSent) "" else totp)
                }
            }
    }
