package net.kirkanos.uptimekuma.feature.settings.server.security

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.kirkanos.uptimekuma.core.domain.AccountRepository
import net.kirkanos.uptimekuma.core.domain.PasswordStrength
import net.kirkanos.uptimekuma.core.domain.ServerSettingsRepository
import net.kirkanos.uptimekuma.core.model.KumaError
import net.kirkanos.uptimekuma.core.model.TwoFactorSetup
import net.kirkanos.uptimekuma.feature.settings.server.common.AdminDone
import net.kirkanos.uptimekuma.feature.settings.server.common.AdminMessage
import net.kirkanos.uptimekuma.feature.settings.server.common.runKuma
import net.kirkanos.uptimekuma.feature.settings.server.common.toKumaError
import javax.inject.Inject

/** The change-password form. */
data class PasswordForm(
    val current: String = "",
    val new: String = "",
    val repeat: String = "",
    val busy: Boolean = false,
) {
    val strength: PasswordStrength? get() = new.takeIf { it.isNotEmpty() }?.let(PasswordStrength::of)
    val mismatch: Boolean get() = repeat.isNotEmpty() && repeat != new
    val canSubmit: Boolean
        get() =
            !busy && current.isNotEmpty() && new.isNotEmpty() && new == repeat &&
                strength != PasswordStrength.TOO_WEAK
}

/** Steps of the 2FA dialogs; [error] is shown inside the dialog. */
sealed interface TwoFactorDialog {
    val busy: Boolean
    val error: KumaError?

    /** Enable, step 1: the current password (`prepare2FA`). */
    data class EnablePassword(
        override val busy: Boolean = false,
        override val error: KumaError? = null,
    ) : TwoFactorDialog

    /** Enable, step 2: secret shown; the user enters a code from the authenticator (`verifyToken` + `save2FA`). */
    data class EnableVerify(
        val setup: TwoFactorSetup,
        val password: String,
        val invalidCode: Boolean = false,
        override val busy: Boolean = false,
        override val error: KumaError? = null,
    ) : TwoFactorDialog

    /** Disable: password + current code (`verifyToken` + `disable2FA`), like the web UI. */
    data class Disable(
        val invalidCode: Boolean = false,
        override val busy: Boolean = false,
        override val error: KumaError? = null,
    ) : TwoFactorDialog
}

data class SecurityUiState(
    val password: PasswordForm = PasswordForm(),
    /** `null` while loading. */
    val twoFactorEnabled: Boolean? = null,
    val twoFactorError: KumaError? = null,
    val twoFactorDialog: TwoFactorDialog? = null,
    /** `null` while loading. */
    val authDisabled: Boolean? = null,
    val authBusy: Boolean = false,
    val authError: KumaError? = null,
    val message: AdminMessage? = null,
)

/** Security screen: change password, two-factor authentication and the dangerous "disable auth" switch. */
@HiltViewModel
class SecurityViewModel
    @Inject
    constructor(
        private val account: AccountRepository,
        private val settings: ServerSettingsRepository,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow(SecurityUiState())
        val state: StateFlow<SecurityUiState> = mutableState.asStateFlow()

        init {
            refresh()
        }

        fun refresh() {
            mutableState.update { it.copy(twoFactorError = null, authError = null) }
            viewModelScope.launch {
                runKuma { account.twoFactorEnabled() }
                    .onSuccess { enabled -> mutableState.update { it.copy(twoFactorEnabled = enabled) } }
                    .onFailure { e -> mutableState.update { it.copy(twoFactorError = e.toKumaError()) } }
            }
            viewModelScope.launch {
                runKuma { settings.load() }
                    .onSuccess { s -> mutableState.update { it.copy(authDisabled = s.disableAuth) } }
                    .onFailure { e -> mutableState.update { it.copy(authError = e.toKumaError()) } }
            }
        }

        // ---- password -------------------------------------------------------------------------------

        fun updatePassword(transform: (PasswordForm) -> PasswordForm) {
            mutableState.update { it.copy(password = transform(it.password).copy(busy = it.password.busy)) }
        }

        /** On success Kuma returns a new token, which the data layer stores; this session stays logged in. */
        fun changePassword() {
            val form = mutableState.value.password
            if (!form.canSubmit) return
            mutableState.update { it.copy(password = form.copy(busy = true)) }
            viewModelScope.launch {
                runKuma { account.changePassword(form.current, form.new) }
                    .onSuccess {
                        mutableState.update {
                            it.copy(password = PasswordForm(), message = AdminMessage.Done(AdminDone.PASSWORD_CHANGED))
                        }
                    }.onFailure { e ->
                        mutableState.update {
                            it.copy(
                                password = it.password.copy(busy = false),
                                message = AdminMessage.Failed(e.toKumaError()),
                            )
                        }
                    }
            }
        }

        // ---- 2FA ------------------------------------------------------------------------------------

        fun startEnableTwoFactor() {
            mutableState.update { it.copy(twoFactorDialog = TwoFactorDialog.EnablePassword()) }
        }

        fun startDisableTwoFactor() {
            mutableState.update { it.copy(twoFactorDialog = TwoFactorDialog.Disable()) }
        }

        fun dismissTwoFactorDialog() {
            mutableState.update { it.copy(twoFactorDialog = null) }
        }

        fun prepareTwoFactor(password: String) {
            val dialog = mutableState.value.twoFactorDialog as? TwoFactorDialog.EnablePassword ?: return
            if (dialog.busy || password.isEmpty()) return
            setDialog(dialog.copy(busy = true, error = null))
            viewModelScope.launch {
                runKuma { account.prepareTwoFactor(password) }
                    .onSuccess { setup -> setDialog(TwoFactorDialog.EnableVerify(setup, password)) }
                    .onFailure { e -> setDialog(dialog.copy(busy = false, error = e.toKumaError())) }
            }
        }

        fun confirmEnableTwoFactor(code: String) {
            val dialog = mutableState.value.twoFactorDialog as? TwoFactorDialog.EnableVerify ?: return
            if (dialog.busy || code.isBlank()) return
            setDialog(dialog.copy(busy = true, error = null, invalidCode = false))
            viewModelScope.launch {
                runKuma {
                    if (account.verifyTwoFactorToken(code, dialog.password)) {
                        account.enableTwoFactor(dialog.password)
                        true
                    } else {
                        false
                    }
                }.onSuccess { enabled ->
                    if (enabled) {
                        finishTwoFactor(enabled = true, AdminDone.TWO_FACTOR_ENABLED)
                    } else {
                        setDialog(dialog.copy(busy = false, invalidCode = true))
                    }
                }.onFailure { e -> setDialog(dialog.copy(busy = false, error = e.toKumaError())) }
            }
        }

        fun confirmDisableTwoFactor(
            password: String,
            code: String,
        ) {
            val dialog = mutableState.value.twoFactorDialog as? TwoFactorDialog.Disable ?: return
            if (dialog.busy || password.isEmpty() || code.isBlank()) return
            setDialog(dialog.copy(busy = true, error = null, invalidCode = false))
            viewModelScope.launch {
                runKuma {
                    if (account.verifyTwoFactorToken(code, password)) {
                        account.disableTwoFactor(password)
                        true
                    } else {
                        false
                    }
                }.onSuccess { disabled ->
                    if (disabled) {
                        finishTwoFactor(enabled = false, AdminDone.TWO_FACTOR_DISABLED)
                    } else {
                        setDialog(dialog.copy(busy = false, invalidCode = true))
                    }
                }.onFailure { e -> setDialog(dialog.copy(busy = false, error = e.toKumaError())) }
            }
        }

        private fun finishTwoFactor(
            enabled: Boolean,
            done: AdminDone,
        ) {
            mutableState.update {
                it.copy(twoFactorDialog = null, twoFactorEnabled = enabled, message = AdminMessage.Done(done))
            }
        }

        private fun setDialog(dialog: TwoFactorDialog) {
            mutableState.update { it.copy(twoFactorDialog = dialog) }
        }

        // ---- disable auth ---------------------------------------------------------------------------

        /** [currentPassword] is required to disable authentication, ignored when enabling it again. */
        fun setAuthDisabled(
            disabled: Boolean,
            currentPassword: String?,
        ) {
            if (mutableState.value.authBusy) return
            mutableState.update { it.copy(authBusy = true) }
            viewModelScope.launch {
                runKuma { settings.setAuthDisabled(disabled, currentPassword.takeIf { disabled }) }
                    .onSuccess {
                        val done = if (disabled) AdminDone.AUTH_DISABLED else AdminDone.AUTH_ENABLED
                        mutableState.update {
                            it.copy(authBusy = false, authDisabled = disabled, message = AdminMessage.Done(done))
                        }
                    }.onFailure { e ->
                        mutableState.update {
                            it.copy(
                                authBusy = false,
                                message = AdminMessage.Failed(e.toKumaError()),
                            )
                        }
                    }
            }
        }

        fun consumeMessage() {
            mutableState.update { it.copy(message = null) }
        }
    }
