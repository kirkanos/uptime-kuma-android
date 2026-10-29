package net.kirkanos.uptimekuma.feature.settings.server.settings

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.kirkanos.uptimekuma.core.model.ServerSettings
import net.kirkanos.uptimekuma.core.ui.theme.KumaTheme
import net.kirkanos.uptimekuma.feature.settings.R
import net.kirkanos.uptimekuma.feature.settings.server.common.AdminInfoCard
import net.kirkanos.uptimekuma.feature.settings.server.common.AdminSectionHeader

/** Limits for a notify-days entry (the web UI accepts any positive number; a year is plenty). */
internal object ExpiryDays {
    const val MAX = 365

    /** Adds [day] to [days] (sorted, unique); `null` when [day] is invalid. */
    fun add(
        days: List<Int>,
        day: Int?,
    ): List<Int>? = day?.takeIf { it in 1..MAX }?.let { (days + it).distinct().sorted() }
}

@Composable
internal fun ExpiryNotificationEntry(
    onBack: () -> Unit,
    viewModel: ServerSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ExpiryNotificationContent(
        state,
        onBack,
        viewModel::update,
        viewModel::save,
        viewModel::load,
        viewModel::consumeMessage,
    )
}

@Composable
internal fun ExpiryNotificationContent(
    state: ServerSettingsUiState,
    onBack: () -> Unit,
    onUpdate: ((ServerSettings) -> ServerSettings) -> Unit,
    onSave: () -> Unit,
    onRetry: () -> Unit,
    onMessageShown: () -> Unit,
) {
    SettingsFormScaffold(
        title = stringResource(R.string.admin_expiry_title),
        state = state,
        onBack = onBack,
        onSave = onSave,
        onRetry = onRetry,
        onMessageShown = onMessageShown,
    ) { draft ->
        AdminInfoCard(stringResource(R.string.admin_expiry_info))
        AdminSectionHeader(stringResource(R.string.admin_expiry_tls))
        DaysEditor(
            days = draft.tlsExpiryNotifyDays,
            onChange = { days -> onUpdate { it.copy(tlsExpiryNotifyDays = days) } },
        )
        draft.domainExpiryNotifyDays?.let { domainDays ->
            AdminSectionHeader(stringResource(R.string.admin_expiry_domain))
            DaysEditor(
                days = domainDays,
                onChange = { days -> onUpdate { it.copy(domainExpiryNotifyDays = days) } },
            )
        }
        TextButton(
            onClick = {
                onUpdate {
                    it.copy(
                        tlsExpiryNotifyDays = ServerSettings.DEFAULT_EXPIRY_NOTIFY_DAYS,
                        domainExpiryNotifyDays =
                            it.domainExpiryNotifyDays?.let {
                                ServerSettings.DEFAULT_EXPIRY_NOTIFY_DAYS
                            },
                    )
                }
            },
            modifier = Modifier.padding(horizontal = 8.dp),
        ) { Text(stringResource(R.string.admin_expiry_defaults)) }
    }
}

/** Removable chips plus a number field to add a day. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DaysEditor(
    days: List<Int>,
    onChange: (List<Int>) -> Unit,
) {
    var input by rememberSaveable { mutableStateOf("") }
    val parsed = input.trim().toIntOrNull()
    val next = ExpiryDays.add(days, parsed)
    val invalid = input.isNotBlank() && next == null
    val add = {
        if (next != null) {
            onChange(next)
            input = ""
        }
    }
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (days.isEmpty()) {
            Text(
                stringResource(R.string.admin_expiry_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            days.forEach { day ->
                val label = pluralStringResource(R.plurals.admin_expiry_days, day, day)
                val remove = stringResource(R.string.admin_expiry_remove, label)
                InputChip(
                    selected = false,
                    onClick = { onChange(days - day) },
                    label = { Text(label) },
                    trailingIcon = {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = remove,
                            Modifier.width(InputChipDefaults.IconSize),
                        )
                    },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = input,
                onValueChange = { value -> input = value.filter(Char::isDigit).take(3) },
                label = { Text(stringResource(R.string.admin_expiry_add_label)) },
                isError = invalid,
                supportingText =
                    if (invalid) {
                        (
                            {
                                Text(
                                    stringResource(R.string.admin_expiry_invalid, ExpiryDays.MAX),
                                )
                            }
                        )
                    } else {
                        null
                    },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { add() }),
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = add, enabled = next != null) { Text(stringResource(R.string.admin_add)) }
        }
    }
}

@Preview(showBackground = true, heightDp = 800)
@Composable
private fun ExpiryNotificationPreview() {
    KumaTheme(dynamicColor = false) {
        ExpiryNotificationContent(previewSettingsState, {}, {}, {}, {}, {})
    }
}

@Preview(showBackground = true, heightDp = 800, locale = "de", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ExpiryNotificationDarkPreview() {
    KumaTheme(dynamicColor = false) {
        ExpiryNotificationContent(previewSettingsState, {}, {}, {}, {}, {})
    }
}
