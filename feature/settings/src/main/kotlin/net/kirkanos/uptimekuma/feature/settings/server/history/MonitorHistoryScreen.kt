package net.kirkanos.uptimekuma.feature.settings.server.history

import android.content.res.Configuration
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.kirkanos.uptimekuma.core.model.ServerSettings
import net.kirkanos.uptimekuma.core.ui.theme.KumaTheme
import net.kirkanos.uptimekuma.feature.settings.R
import net.kirkanos.uptimekuma.feature.settings.server.common.AdminConfirmDialog
import net.kirkanos.uptimekuma.feature.settings.server.common.AdminInfoCard
import net.kirkanos.uptimekuma.feature.settings.server.common.AdminMessageSnackbar
import net.kirkanos.uptimekuma.feature.settings.server.common.AdminSectionHeader
import net.kirkanos.uptimekuma.feature.settings.server.common.adminErrorText
import net.kirkanos.uptimekuma.feature.settings.server.settings.ServerSettingsUiState
import net.kirkanos.uptimekuma.feature.settings.server.settings.ServerSettingsViewModel
import net.kirkanos.uptimekuma.feature.settings.server.settings.SettingsFormScaffold
import net.kirkanos.uptimekuma.feature.settings.server.settings.SettingsProblem
import net.kirkanos.uptimekuma.feature.settings.server.settings.previewSettingsState

@Composable
internal fun MonitorHistoryEntry(
    onBack: () -> Unit,
    settingsViewModel: ServerSettingsViewModel = hiltViewModel(),
    databaseViewModel: DatabaseViewModel = hiltViewModel(),
) {
    val settings by settingsViewModel.state.collectAsStateWithLifecycle()
    val database by databaseViewModel.state.collectAsStateWithLifecycle()
    MonitorHistoryContent(
        settings = settings,
        database = database,
        actions =
            MonitorHistoryActions(
                onBack = onBack,
                onUpdate = settingsViewModel::update,
                onSave = settingsViewModel::save,
                onRetry = settingsViewModel::load,
                onSettingsMessageShown = settingsViewModel::consumeMessage,
                onShrink = databaseViewModel::shrink,
                onClearStatistics = databaseViewModel::clearStatistics,
                onDatabaseMessageShown = databaseViewModel::consumeMessage,
            ),
    )
}

internal data class MonitorHistoryActions(
    val onBack: () -> Unit = {},
    val onUpdate: ((ServerSettings) -> ServerSettings) -> Unit = {},
    val onSave: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onSettingsMessageShown: () -> Unit = {},
    val onShrink: () -> Unit = {},
    val onClearStatistics: () -> Unit = {},
    val onDatabaseMessageShown: () -> Unit = {},
)

@Composable
internal fun MonitorHistoryContent(
    settings: ServerSettingsUiState,
    database: DatabaseUiState,
    actions: MonitorHistoryActions,
) {
    val snackbar = remember { SnackbarHostState() }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    AdminMessageSnackbar(database.message, snackbar, actions.onDatabaseMessageShown)
    SettingsFormScaffold(
        title = stringResource(R.string.admin_history_title),
        state = settings,
        onBack = actions.onBack,
        onSave = actions.onSave,
        onRetry = actions.onRetry,
        onMessageShown = actions.onSettingsMessageShown,
        snackbarHostState = snackbar,
    ) { draft ->
        KeepDaysField(draft, SettingsProblem.KEEP_DAYS in settings.problems, actions.onUpdate)
        HorizontalDivider(Modifier.padding(top = 8.dp))
        AdminSectionHeader(stringResource(R.string.admin_history_database))
        DatabaseSize(settings, database, actions.onShrink)
        HorizontalDivider(Modifier.padding(top = 8.dp))
        AdminSectionHeader(stringResource(R.string.admin_history_danger))
        AdminInfoCard(stringResource(R.string.admin_history_clear_info), warning = true)
        OutlinedButton(
            onClick = { confirmClear = true },
            enabled = database.running == null,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            if (database.running == DatabaseOperation.CLEAR_STATISTICS) {
                CircularProgressIndicator(Modifier.size(18.dp).padding(end = 8.dp), strokeWidth = 2.dp)
            }
            Text(stringResource(R.string.admin_history_clear))
        }
    }
    if (confirmClear) {
        AdminConfirmDialog(
            title = stringResource(R.string.admin_history_clear_title),
            message = stringResource(R.string.admin_history_clear_message),
            confirmLabel = stringResource(R.string.admin_history_clear),
            onConfirm = {
                confirmClear = false
                actions.onClearStatistics()
            },
            onDismiss = { confirmClear = false },
        )
    }
}

@Composable
private fun KeepDaysField(
    draft: ServerSettings,
    invalid: Boolean,
    onUpdate: ((ServerSettings) -> ServerSettings) -> Unit,
) {
    var text by rememberSaveable(draft.keepDataPeriodDays) { mutableStateOf(draft.keepDataPeriodDays.toString()) }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { value ->
                text = value.filter { it.isDigit() }.take(MAX_DIGITS)
                text.toIntOrNull()?.let { days -> onUpdate { it.copy(keepDataPeriodDays = days) } }
            },
            label = { Text(stringResource(R.string.admin_history_keep_days)) },
            isError = invalid || text.isBlank(),
            supportingText = {
                val days = text.toIntOrNull()
                Text(
                    when (days) {
                        null -> stringResource(R.string.admin_history_keep_days_hint)
                        0 -> stringResource(R.string.admin_history_keep_forever)
                        else -> pluralStringResource(R.plurals.admin_history_keep_for, days, days)
                    },
                )
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun DatabaseSize(
    settings: ServerSettingsUiState,
    database: DatabaseUiState,
    onShrink: () -> Unit,
) {
    val context = LocalContext.current
    ListItem(
        headlineContent = { Text(stringResource(R.string.admin_history_database_size)) },
        supportingContent = {
            Text(
                when {
                    database.sizeBytes != null -> Formatter.formatShortFileSize(context, database.sizeBytes)
                    database.sizeError != null -> adminErrorText(database.sizeError)
                    else -> "…"
                },
            )
        },
        overlineContent = settings.details?.dbType?.let { { Text(it) } },
    )
    if (settings.details?.isSqlite == true) {
        Row(
            Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(onClick = onShrink, enabled = database.running == null) {
                Text(stringResource(R.string.admin_history_shrink))
            }
            if (database.running == DatabaseOperation.SHRINK) CircularProgressIndicator(Modifier.size(24.dp))
        }
        AdminInfoCard(stringResource(R.string.admin_history_shrink_info))
    }
}

private const val MAX_DIGITS = 5

@Preview(showBackground = true, heightDp = 1000)
@Composable
private fun MonitorHistoryPreview() {
    KumaTheme(dynamicColor = false) {
        MonitorHistoryContent(previewSettingsState, DatabaseUiState(sizeBytes = 12_345_678), MonitorHistoryActions())
    }
}

@Preview(showBackground = true, heightDp = 1000, locale = "de", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun MonitorHistoryDarkPreview() {
    KumaTheme(dynamicColor = false) {
        MonitorHistoryContent(
            previewSettingsState,
            DatabaseUiState(sizeBytes = 1_048_576, running = DatabaseOperation.SHRINK),
            MonitorHistoryActions(),
        )
    }
}
