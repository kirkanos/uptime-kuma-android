package net.kirkanos.uptimekuma.feature.settings.server.security

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.kirkanos.uptimekuma.core.domain.PasswordStrength
import net.kirkanos.uptimekuma.core.ui.theme.KumaTheme
import net.kirkanos.uptimekuma.feature.settings.R
import net.kirkanos.uptimekuma.feature.settings.server.common.AdminInfoCard
import net.kirkanos.uptimekuma.feature.settings.server.common.AdminMessageSnackbar
import net.kirkanos.uptimekuma.feature.settings.server.common.AdminScaffold
import net.kirkanos.uptimekuma.feature.settings.server.common.AdminSectionHeader
import net.kirkanos.uptimekuma.feature.settings.server.common.AdminSwitchRow
import net.kirkanos.uptimekuma.feature.settings.server.common.SecretField
import net.kirkanos.uptimekuma.feature.settings.server.common.adminErrorText

@Composable
internal fun SecurityEntry(
    onBack: () -> Unit,
    viewModel: SecurityViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SecurityContent(
        state = state,
        actions =
            SecurityActions(
                onBack = onBack,
                onPassword = viewModel::updatePassword,
                onChangePassword = viewModel::changePassword,
                onEnableTwoFactor = viewModel::startEnableTwoFactor,
                onDisableTwoFactor = viewModel::startDisableTwoFactor,
                onPrepareTwoFactor = viewModel::prepareTwoFactor,
                onConfirmEnable = viewModel::confirmEnableTwoFactor,
                onConfirmDisable = viewModel::confirmDisableTwoFactor,
                onDismissTwoFactor = viewModel::dismissTwoFactorDialog,
                onSetAuthDisabled = viewModel::setAuthDisabled,
                onRetry = viewModel::refresh,
                onMessageShown = viewModel::consumeMessage,
            ),
    )
}

internal data class SecurityActions(
    val onBack: () -> Unit = {},
    val onPassword: ((PasswordForm) -> PasswordForm) -> Unit = {},
    val onChangePassword: () -> Unit = {},
    val onEnableTwoFactor: () -> Unit = {},
    val onDisableTwoFactor: () -> Unit = {},
    val onPrepareTwoFactor: (String) -> Unit = {},
    val onConfirmEnable: (String) -> Unit = {},
    val onConfirmDisable: (String, String) -> Unit = { _, _ -> },
    val onDismissTwoFactor: () -> Unit = {},
    val onSetAuthDisabled: (Boolean, String?) -> Unit = { _, _ -> },
    val onRetry: () -> Unit = {},
    val onMessageShown: () -> Unit = {},
)

@Composable
internal fun SecurityContent(
    state: SecurityUiState,
    actions: SecurityActions,
) {
    val snackbar = remember { SnackbarHostState() }
    var authConfirm by rememberSaveable { mutableStateOf<AuthConfirm?>(null) }
    AdminMessageSnackbar(state.message, snackbar, actions.onMessageShown)
    AdminScaffold(
        title = stringResource(R.string.admin_security_title),
        onBack = actions.onBack,
        snackbarHostState = snackbar,
    ) { modifier ->
        Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            AdminSectionHeader(stringResource(R.string.admin_password_title))
            ChangePasswordForm(state.password, actions.onPassword, actions.onChangePassword)

            HorizontalDivider(Modifier.padding(top = 8.dp))
            AdminSectionHeader(stringResource(R.string.admin_2fa_title))
            TwoFactorSection(state, actions)

            HorizontalDivider(Modifier.padding(top = 8.dp))
            AdminSectionHeader(stringResource(R.string.admin_advanced))
            when (val disabled = state.authDisabled) {
                null -> {
                    state.authError?.let {
                        ListItem(
                            headlineContent = { Text(adminErrorText(it), color = MaterialTheme.colorScheme.error) },
                            trailingContent = {
                                TextButton(
                                    onClick = actions.onRetry,
                                ) { Text(stringResource(R.string.admin_retry)) }
                            },
                        )
                    } ?: LinearProgressIndicator(Modifier.padding(16.dp))
                }

                else -> {
                    if (disabled) AdminInfoCard(stringResource(R.string.admin_auth_disabled_warning), warning = true)
                    AdminSwitchRow(
                        title = stringResource(R.string.admin_auth_disable),
                        summary = stringResource(R.string.admin_auth_disable_summary),
                        checked = disabled,
                        enabled = !state.authBusy,
                        onCheckedChange = { checked ->
                            authConfirm =
                                if (checked) AuthConfirm.WARN else AuthConfirm.ENABLE
                        },
                    )
                }
            }
        }
    }
    TwoFactorDialogs(state.twoFactorDialog, actions)
    AuthDialogs(
        step = authConfirm,
        onStep = { authConfirm = it },
        onDisable = { password ->
            authConfirm = null
            actions.onSetAuthDisabled(true, password)
        },
        onEnable = {
            authConfirm = null
            actions.onSetAuthDisabled(false, null)
        },
    )
}

@Composable
private fun ChangePasswordForm(
    form: PasswordForm,
    onChange: ((PasswordForm) -> PasswordForm) -> Unit,
    onSubmit: () -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SecretField(
            form.current,
            { v -> onChange { it.copy(current = v) } },
            stringResource(R.string.admin_current_password),
        )
        SecretField(
            value = form.new,
            onValueChange = { v -> onChange { it.copy(new = v) } },
            label = stringResource(R.string.admin_new_password),
            isError = form.strength == PasswordStrength.TOO_WEAK,
        )
        form.strength?.let { StrengthMeter(it) }
        SecretField(
            value = form.repeat,
            onValueChange = { v -> onChange { it.copy(repeat = v) } },
            label = stringResource(R.string.admin_repeat_password),
            isError = form.mismatch,
            supportingText = if (form.mismatch) stringResource(R.string.admin_password_mismatch) else null,
        )
        Text(
            stringResource(R.string.admin_password_info),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onSubmit, enabled = form.canSubmit) {
            Text(stringResource(if (form.busy) R.string.admin_saving else R.string.admin_password_change))
        }
    }
}

@Composable
private fun StrengthMeter(strength: PasswordStrength) {
    val (label, color) =
        when (strength) {
            PasswordStrength.TOO_WEAK -> R.string.admin_strength_too_weak to MaterialTheme.colorScheme.error
            PasswordStrength.WEAK -> R.string.admin_strength_weak to Color(0xFFF8A306)
            PasswordStrength.MEDIUM -> R.string.admin_strength_medium to Color(0xFF8BC34A)
            PasswordStrength.STRONG -> R.string.admin_strength_strong to Color(0xFF5CDD8B)
        }
    Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        LinearProgressIndicator(
            progress = { (strength.ordinal + 1) / PasswordStrength.entries.size.toFloat() },
            color = color,
        )
        Text(
            stringResource(R.string.admin_strength, stringResource(label)),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun TwoFactorSection(
    state: SecurityUiState,
    actions: SecurityActions,
) {
    val enabled = state.twoFactorEnabled
    ListItem(
        headlineContent = {
            Text(
                when (enabled) {
                    null -> stringResource(R.string.admin_2fa_loading)
                    true -> stringResource(R.string.admin_2fa_on)
                    false -> stringResource(R.string.admin_2fa_off)
                },
            )
        },
        supportingContent =
            state.twoFactorError?.let {
                {
                    Text(
                        adminErrorText(it),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
        leadingContent = {
            Icon(
                if (enabled ==
                    true
                ) {
                    Icons.Outlined.Security
                } else {
                    Icons.Outlined.LockOpen
                },
                contentDescription = null,
            )
        },
        trailingContent = {
            when (enabled) {
                true -> {
                    OutlinedButton(
                        onClick = actions.onDisableTwoFactor,
                    ) { Text(stringResource(R.string.admin_2fa_disable)) }
                }

                false -> {
                    Button(onClick = actions.onEnableTwoFactor) { Text(stringResource(R.string.admin_2fa_enable)) }
                }

                null -> {
                    if (state.twoFactorError !=
                        null
                    ) {
                        TextButton(onClick = actions.onRetry) { Text(stringResource(R.string.admin_retry)) }
                    }
                }
            }
        },
    )
}

@Preview(showBackground = true, heightDp = 1100)
@Composable
private fun SecurityPreview() {
    KumaTheme(dynamicColor = false) {
        SecurityContent(
            SecurityUiState(
                password = PasswordForm(current = "x", new = "abcDEF12", repeat = "abc"),
                twoFactorEnabled = false,
                authDisabled = false,
            ),
            SecurityActions(),
        )
    }
}

@Preview(showBackground = true, heightDp = 1100, locale = "de", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SecurityDarkPreview() {
    KumaTheme(dynamicColor = false) {
        SecurityContent(SecurityUiState(twoFactorEnabled = true, authDisabled = true), SecurityActions())
    }
}
