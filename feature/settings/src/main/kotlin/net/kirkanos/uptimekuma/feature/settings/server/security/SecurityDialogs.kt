package net.kirkanos.uptimekuma.feature.settings.server.security

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import net.kirkanos.uptimekuma.core.model.TwoFactorSetup
import net.kirkanos.uptimekuma.core.ui.theme.KumaTheme
import net.kirkanos.uptimekuma.feature.settings.R
import net.kirkanos.uptimekuma.feature.settings.server.common.AdminConfirmDialog
import net.kirkanos.uptimekuma.feature.settings.server.common.AdminFormDialog
import net.kirkanos.uptimekuma.feature.settings.server.common.AdminInfoCard
import net.kirkanos.uptimekuma.feature.settings.server.common.SecretField
import net.kirkanos.uptimekuma.feature.settings.server.common.adminErrorText
import net.kirkanos.uptimekuma.feature.settings.server.common.rememberSecretCopier

private const val CODE_LENGTH = 6

@Composable
internal fun TwoFactorDialogs(
    dialog: TwoFactorDialog?,
    actions: SecurityActions,
) {
    when (dialog) {
        null -> {}

        is TwoFactorDialog.EnablePassword -> {
            EnablePasswordDialog(dialog, actions)
        }

        is TwoFactorDialog.EnableVerify -> {
            EnableVerifyDialog(dialog, actions)
        }

        is TwoFactorDialog.Disable -> {
            DisableDialog(dialog, actions)
        }
    }
}

@Composable
private fun EnablePasswordDialog(
    dialog: TwoFactorDialog.EnablePassword,
    actions: SecurityActions,
) {
    var password by rememberSaveable { mutableStateOf("") }
    AdminFormDialog(
        title = stringResource(R.string.admin_2fa_enable),
        confirmLabel = stringResource(R.string.admin_next),
        confirmEnabled = password.isNotEmpty() && !dialog.busy,
        onConfirm = { actions.onPrepareTwoFactor(password) },
        onDismiss = actions.onDismissTwoFactor,
    ) {
        Text(stringResource(R.string.admin_2fa_enable_intro))
        SecretField(password, { password = it }, stringResource(R.string.admin_current_password))
        DialogStatus(dialog)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EnableVerifyDialog(
    dialog: TwoFactorDialog.EnableVerify,
    actions: SecurityActions,
) {
    val context = LocalContext.current
    val copy = rememberSecretCopier()
    var code by rememberSaveable { mutableStateOf("") }
    var noApp by rememberSaveable { mutableStateOf(false) }
    AdminFormDialog(
        title = stringResource(R.string.admin_2fa_enable),
        confirmLabel = stringResource(R.string.admin_2fa_verify_and_enable),
        confirmEnabled = code.length == CODE_LENGTH && !dialog.busy,
        onConfirm = { actions.onConfirmEnable(code) },
        onDismiss = actions.onDismissTwoFactor,
    ) {
        Text(stringResource(R.string.admin_2fa_scan_intro))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    noApp =
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, dialog.setup.uri.toUri()))
                            false
                        } catch (_: ActivityNotFoundException) {
                            true
                        }
                },
            ) {
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null)
                Text(stringResource(R.string.admin_2fa_open_app))
            }
            dialog.setup.secret?.let { secret ->
                val label = stringResource(R.string.admin_2fa_secret)
                OutlinedButton(onClick = { copy(label, secret) }) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                    Text(stringResource(R.string.admin_2fa_copy_secret))
                }
            }
        }
        if (noApp) Text(stringResource(R.string.admin_2fa_no_app), color = MaterialTheme.colorScheme.error)
        dialog.setup.secret?.let { secret ->
            Text(stringResource(R.string.admin_2fa_secret), style = MaterialTheme.typography.labelMedium)
            SelectionContainer {
                Text(secret.chunked(4).joinToString(" "), fontFamily = FontFamily.Monospace)
            }
        }
        CodeField(code, { code = it }, dialog.invalidCode)
        DialogStatus(dialog)
    }
}

@Composable
private fun DisableDialog(
    dialog: TwoFactorDialog.Disable,
    actions: SecurityActions,
) {
    var password by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    AdminFormDialog(
        title = stringResource(R.string.admin_2fa_disable),
        confirmLabel = stringResource(R.string.admin_2fa_disable),
        confirmEnabled = password.isNotEmpty() && code.length == CODE_LENGTH && !dialog.busy,
        onConfirm = { actions.onConfirmDisable(password, code) },
        onDismiss = actions.onDismissTwoFactor,
    ) {
        Text(stringResource(R.string.admin_2fa_disable_intro))
        SecretField(password, { password = it }, stringResource(R.string.admin_current_password))
        CodeField(code, { code = it }, dialog.invalidCode)
        DialogStatus(dialog)
    }
}

@Composable
private fun CodeField(
    code: String,
    onChange: (String) -> Unit,
    invalid: Boolean,
) {
    OutlinedTextField(
        value = code,
        onValueChange = { value -> onChange(value.filter(Char::isDigit).take(CODE_LENGTH)) },
        label = { Text(stringResource(R.string.admin_2fa_code)) },
        isError = invalid,
        supportingText = if (invalid) ({ Text(stringResource(R.string.admin_error_invalid_token)) }) else null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun DialogStatus(dialog: TwoFactorDialog) {
    if (dialog.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    dialog.error?.let { Text(adminErrorText(it), color = MaterialTheme.colorScheme.error) }
}

/** Steps of the "disable authentication" confirmation (two dialogs, the second asks for the password). */
internal enum class AuthConfirm { WARN, PASSWORD, ENABLE }

/** Double confirmation before disabling authentication; a single one before enabling it again. */
@Composable
internal fun AuthDialogs(
    step: AuthConfirm?,
    onStep: (AuthConfirm?) -> Unit,
    onDisable: (String) -> Unit,
    onEnable: () -> Unit,
) {
    when (step) {
        null -> {}

        AuthConfirm.WARN -> {
            AdminConfirmDialog(
                title = stringResource(R.string.admin_auth_disable_title),
                message = stringResource(R.string.admin_auth_disable_warning),
                confirmLabel = stringResource(R.string.admin_next),
                onConfirm = { onStep(AuthConfirm.PASSWORD) },
                onDismiss = { onStep(null) },
            )
        }

        AuthConfirm.PASSWORD -> {
            DisableAuthPasswordDialog(onConfirm = onDisable, onDismiss = { onStep(null) })
        }

        AuthConfirm.ENABLE -> {
            AdminConfirmDialog(
                title = stringResource(R.string.admin_auth_enable_title),
                message = stringResource(R.string.admin_auth_enable_message),
                confirmLabel = stringResource(R.string.admin_auth_enable),
                destructive = false,
                onConfirm = onEnable,
                onDismiss = { onStep(null) },
            )
        }
    }
}

@Composable
private fun DisableAuthPasswordDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by rememberSaveable { mutableStateOf("") }
    var understood by rememberSaveable { mutableStateOf(false) }
    AdminFormDialog(
        title = stringResource(R.string.admin_auth_disable_title),
        confirmLabel = stringResource(R.string.admin_auth_disable_confirm),
        confirmEnabled = password.isNotEmpty() && understood,
        onConfirm = { onConfirm(password) },
        onDismiss = onDismiss,
    ) {
        AdminInfoCard(stringResource(R.string.admin_auth_disable_final), warning = true)
        SecretField(password, { password = it }, stringResource(R.string.admin_current_password))
        Row(
            Modifier.fillMaxWidth().toggleable(value = understood, role = Role.Checkbox, onValueChange = {
                understood =
                    it
            }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = understood, onCheckedChange = null)
            Text(stringResource(R.string.admin_auth_disable_understood))
        }
    }
}

@Preview
@Composable
private fun EnableVerifyDialogPreview() {
    KumaTheme(dynamicColor = false) {
        EnableVerifyDialog(
            TwoFactorDialog.EnableVerify(
                TwoFactorSetup("otpauth://totp/Uptime%20Kuma:admin?secret=JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP"),
                password = "x",
                invalidCode = true,
            ),
            SecurityActions(),
        )
    }
}

@Preview
@Composable
private fun DisableAuthPasswordDialogPreview() {
    KumaTheme(dynamicColor = false) {
        DisableAuthPasswordDialog(onConfirm = {}, onDismiss = {})
    }
}
