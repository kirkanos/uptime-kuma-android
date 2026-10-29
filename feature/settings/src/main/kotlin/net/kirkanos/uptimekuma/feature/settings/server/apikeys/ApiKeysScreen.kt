package net.kirkanos.uptimekuma.feature.settings.server.apikeys

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.kirkanos.uptimekuma.core.model.ApiKey
import net.kirkanos.uptimekuma.core.model.ApiKeyStatus
import net.kirkanos.uptimekuma.core.model.CreatedApiKey
import net.kirkanos.uptimekuma.core.ui.component.EmptyState
import net.kirkanos.uptimekuma.core.ui.component.ErrorState
import net.kirkanos.uptimekuma.core.ui.component.LoadingState
import net.kirkanos.uptimekuma.core.ui.format.currentLocale
import net.kirkanos.uptimekuma.core.ui.theme.KumaTheme
import net.kirkanos.uptimekuma.feature.settings.R
import net.kirkanos.uptimekuma.feature.settings.main.TimePickerDialog
import net.kirkanos.uptimekuma.feature.settings.server.common.AdminConfirmDialog
import net.kirkanos.uptimekuma.feature.settings.server.common.AdminFormDialog
import net.kirkanos.uptimekuma.feature.settings.server.common.AdminInfoCard
import net.kirkanos.uptimekuma.feature.settings.server.common.AdminMessageSnackbar
import net.kirkanos.uptimekuma.feature.settings.server.common.AdminScaffold
import net.kirkanos.uptimekuma.feature.settings.server.common.adminErrorText
import net.kirkanos.uptimekuma.feature.settings.server.common.rememberSecretCopier
import net.kirkanos.uptimekuma.feature.settings.server.infra.ToggleRow
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private const val MINUTES_PER_HOUR = 60

@Composable
internal fun ApiKeysEntry(
    onBack: () -> Unit,
    viewModel: ApiKeysViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ApiKeysContent(
        state = state,
        actions =
            ApiKeysActions(
                onBack = onBack,
                onRetry = viewModel::refresh,
                onAdd = viewModel::startCreate,
                onForm = viewModel::updateForm,
                canCreate = viewModel::canCreate,
                onCreate = viewModel::create,
                onDismissForm = viewModel::dismissForm,
                onDismissCreated = viewModel::dismissCreated,
                onSetActive = viewModel::setActive,
                onDelete = viewModel::delete,
                onMessageShown = viewModel::consumeMessage,
            ),
    )
}

internal data class ApiKeysActions(
    val onBack: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onAdd: () -> Unit = {},
    val onForm: ((ApiKeyForm) -> ApiKeyForm) -> Unit = {},
    val canCreate: (ApiKeyForm) -> Boolean = { true },
    val onCreate: () -> Unit = {},
    val onDismissForm: () -> Unit = {},
    val onDismissCreated: () -> Unit = {},
    val onSetActive: (Long, Boolean) -> Unit = { _, _ -> },
    val onDelete: (Long) -> Unit = {},
    val onMessageShown: () -> Unit = {},
)

@Composable
internal fun ApiKeysContent(
    state: ApiKeysUiState,
    actions: ApiKeysActions,
) {
    val snackbar = remember { SnackbarHostState() }
    var confirmDelete by rememberSaveable { mutableStateOf<Long?>(null) }
    AdminMessageSnackbar(state.message, snackbar, actions.onMessageShown)
    AdminScaffold(
        title = stringResource(R.string.admin_api_keys_title),
        onBack = actions.onBack,
        snackbarHostState = snackbar,
        floatingActionButton = {
            if (state.keys != null) {
                ExtendedFloatingActionButton(
                    onClick = actions.onAdd,
                    icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.admin_api_key_add)) },
                )
            }
        },
    ) { modifier ->
        val keys = state.keys
        when {
            keys == null && state.loadError != null -> {
                ErrorState(state.loadError, modifier, actions.onRetry)
            }

            keys == null -> {
                LoadingState(modifier)
            }

            keys.isEmpty() -> {
                EmptyState(
                    title = stringResource(R.string.admin_api_keys_empty),
                    message = stringResource(R.string.admin_api_keys_info),
                    icon = Icons.Outlined.Key,
                    modifier = modifier,
                )
            }

            else -> {
                LazyColumn(modifier.fillMaxSize()) {
                    item { AdminInfoCard(stringResource(R.string.admin_api_keys_info)) }
                    items(keys, key = { it.id }) { key ->
                        ApiKeyRow(key, key.id in state.busyIds, actions) { confirmDelete = key.id }
                    }
                }
            }
        }
    }
    state.form?.let { ApiKeyFormDialog(it, actions) }
    state.created?.let { CreatedKeyDialog(it, actions.onDismissCreated) }
    confirmDelete?.let { id ->
        AdminConfirmDialog(
            title = stringResource(R.string.admin_delete_title),
            message =
                stringResource(
                    R.string.admin_api_key_delete_message,
                    state.keys
                        ?.firstOrNull { it.id == id }
                        ?.name
                        .orEmpty(),
                ),
            confirmLabel = stringResource(R.string.admin_delete),
            onConfirm = {
                confirmDelete = null
                actions.onDelete(id)
            },
            onDismiss = { confirmDelete = null },
        )
    }
}

@Composable
private fun ApiKeyRow(
    key: ApiKey,
    busy: Boolean,
    actions: ApiKeysActions,
    onDelete: () -> Unit,
) {
    val locale = currentLocale()
    val status = stringResource(key.status.labelRes())
    val expires =
        key.expires?.let { stringResource(R.string.admin_api_key_expires, formatLocal(it, locale)) }
            ?: stringResource(R.string.admin_api_key_never_expires)
    val created = key.createdDate?.let { stringResource(R.string.admin_api_key_created, formatUtc(it, locale)) }
    ListItem(
        overlineContent = {
            Text(
                status,
                color =
                    if (key.status ==
                        ApiKeyStatus.ACTIVE
                    ) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
            )
        },
        headlineContent = { Text("${key.name} (uk${key.id})") },
        supportingContent = { Text(listOfNotNull(expires, created).joinToString("\n")) },
        trailingContent = {
            if (busy) {
                CircularProgressIndicator(Modifier.padding(12.dp))
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val toggleLabel =
                        stringResource(
                            if (key.active) R.string.admin_api_key_disable else R.string.admin_api_key_enable,
                            key.name,
                        )
                    Switch(
                        checked = key.active,
                        onCheckedChange = { actions.onSetActive(key.id, it) },
                        modifier = Modifier.semantics { contentDescription = toggleLabel },
                    )
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = stringResource(R.string.admin_delete_named, key.name),
                        )
                    }
                }
            }
        },
    )
}

private fun ApiKeyStatus.labelRes(): Int =
    when (this) {
        ApiKeyStatus.ACTIVE -> R.string.admin_api_key_status_active
        ApiKeyStatus.INACTIVE -> R.string.admin_api_key_status_inactive
        ApiKeyStatus.EXPIRED -> R.string.admin_api_key_status_expired
        ApiKeyStatus.UNKNOWN -> R.string.admin_api_key_status_unknown
    }

private fun formatLocal(
    value: LocalDateTime,
    locale: Locale,
): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale).format(value)

/** `createdDate` is stored in UTC. */
private fun formatUtc(
    value: LocalDateTime,
    locale: Locale,
): String =
    formatLocal(value.atOffset(ZoneOffset.UTC).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime(), locale)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ApiKeyFormDialog(
    form: ApiKeyForm,
    actions: ApiKeysActions,
) {
    var pickDate by rememberSaveable { mutableStateOf(false) }
    var pickTime by rememberSaveable { mutableStateOf(false) }
    val locale = currentLocale()
    AdminFormDialog(
        title = stringResource(R.string.admin_api_key_add),
        confirmLabel = stringResource(if (form.saving) R.string.admin_saving else R.string.admin_api_key_generate),
        confirmEnabled = actions.canCreate(form),
        onConfirm = actions.onCreate,
        onDismiss = actions.onDismissForm,
    ) {
        OutlinedTextField(
            value = form.name,
            onValueChange = { v -> actions.onForm { it.copy(name = v) } },
            label = { Text(stringResource(R.string.admin_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        ToggleRow(stringResource(R.string.admin_api_key_never_expires), form.expires == null) { never ->
            actions.onForm {
                it.copy(
                    expires =
                        if (never) {
                            null
                        } else {
                            LocalDateTime
                                .now()
                                .plusMonths(1)
                                .withSecond(0)
                                .withNano(0)
                        },
                )
            }
        }
        form.expires?.let { expires ->
            Text(stringResource(R.string.admin_api_key_expiry_date), style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickDate = true }) {
                    Text(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(expires))
                }
                OutlinedButton(onClick = { pickTime = true }) {
                    Text(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(expires))
                }
            }
            if (!expires.isAfter(LocalDateTime.now())) {
                Text(stringResource(R.string.admin_api_key_expiry_past), color = MaterialTheme.colorScheme.error)
            }
        }
        ToggleRow(stringResource(R.string.admin_api_key_active), form.active, switch = true) { v ->
            actions.onForm {
                it.copy(active = v)
            }
        }
        form.error?.let { Text(adminErrorText(it), color = MaterialTheme.colorScheme.error) }
    }
    val expires = form.expires
    if (pickDate && expires != null) {
        val pickerState =
            rememberDatePickerState(
                initialSelectedDateMillis =
                    expires
                        .toLocalDate()
                        .atStartOfDay(
                            ZoneOffset.UTC,
                        ).toInstant()
                        .toEpochMilli(),
            )
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickDate = false
                        pickerState.selectedDateMillis?.let { millis ->
                            val date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                            actions.onForm { it.copy(expires = date.atTime(expires.toLocalTime())) }
                        }
                    },
                ) { Text(stringResource(R.string.admin_ok)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { pickDate = false },
                ) { Text(stringResource(R.string.settings_cancel)) }
            },
        ) { DatePicker(pickerState) }
    }
    if (pickTime && expires != null) {
        TimePickerDialog(
            title = stringResource(R.string.admin_api_key_expiry_date),
            initialMinuteOfDay = expires.hour * MINUTES_PER_HOUR + expires.minute,
            onDismiss = { pickTime = false },
            onConfirm = { minute ->
                pickTime = false
                actions.onForm {
                    it.copy(
                        expires =
                            expires.withHour(minute / MINUTES_PER_HOUR).withMinute(minute % MINUTES_PER_HOUR),
                    )
                }
            },
        )
    }
}

@Composable
private fun CreatedKeyDialog(
    created: CreatedApiKey,
    onDismiss: () -> Unit,
) {
    val copy = rememberSecretCopier()
    val label = stringResource(R.string.admin_api_key)
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.admin_api_key_created_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AdminInfoCard(stringResource(R.string.admin_api_key_created_warning), warning = true)
                SelectionContainer { Text(created.key, fontFamily = FontFamily.Monospace) }
                OutlinedButton(onClick = { copy(label, created.key) }) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                    Text(stringResource(R.string.admin_copy))
                }
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text(stringResource(R.string.admin_done)) } },
    )
}

private val previewKeys =
    listOf(
        ApiKey(1, "Grafana", true, ApiKeyStatus.ACTIVE, LocalDateTime.of(2026, 10, 1, 8, 0), null),
        ApiKey(
            2,
            "Old",
            false,
            ApiKeyStatus.INACTIVE,
            LocalDateTime.of(2026, 1, 1, 8, 0),
            LocalDateTime.of(2027, 1, 1, 0, 0),
        ),
        ApiKey(
            3,
            "Expired",
            true,
            ApiKeyStatus.EXPIRED,
            LocalDateTime.of(2025, 1, 1, 8, 0),
            LocalDateTime.of(2025, 6, 1, 0, 0),
        ),
    )

@Preview(showBackground = true)
@Composable
private fun ApiKeysPreview() {
    KumaTheme(dynamicColor = false) {
        ApiKeysContent(ApiKeysUiState(keys = previewKeys), ApiKeysActions())
    }
}

@Preview(showBackground = true, locale = "de", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ApiKeyFormPreview() {
    KumaTheme(dynamicColor = false) {
        ApiKeysContent(
            ApiKeysUiState(
                keys = previewKeys,
                form = ApiKeyForm(name = "Grafana", expires = LocalDateTime.of(2030, 1, 1, 12, 0)),
            ),
            ApiKeysActions(),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ApiKeyCreatedPreview() {
    KumaTheme(dynamicColor = false) {
        ApiKeysContent(
            ApiKeysUiState(
                keys = emptyList(),
                created = CreatedApiKey(4, "uk4_0123456789abcdefghijABCDEFGHIJ0123456789"),
            ),
            ApiKeysActions(),
        )
    }
}
