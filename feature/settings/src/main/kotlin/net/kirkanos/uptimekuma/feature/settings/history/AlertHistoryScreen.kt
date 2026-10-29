package net.kirkanos.uptimekuma.feature.settings.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.kirkanos.uptimekuma.core.model.AlertRecord
import net.kirkanos.uptimekuma.core.model.DisplayStatus
import net.kirkanos.uptimekuma.core.model.MonitorStatus
import net.kirkanos.uptimekuma.core.ui.component.EmptyState
import net.kirkanos.uptimekuma.core.ui.component.LoadingState
import net.kirkanos.uptimekuma.core.ui.component.StatusDot
import net.kirkanos.uptimekuma.core.ui.component.labelRes
import net.kirkanos.uptimekuma.core.ui.format.currentLocale
import net.kirkanos.uptimekuma.core.ui.format.formatDateTime
import net.kirkanos.uptimekuma.core.ui.theme.KumaTheme
import net.kirkanos.uptimekuma.feature.settings.R

@Composable
internal fun AlertHistoryEntry(
    onBack: () -> Unit,
    onMonitorClick: (Long) -> Unit,
    viewModel: AlertHistoryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AlertHistoryContent(state = state, onBack = onBack, onMonitorClick = onMonitorClick, onClear = viewModel::clear)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AlertHistoryContent(
    state: AlertHistoryUiState,
    onBack: () -> Unit,
    onMonitorClick: (Long) -> Unit,
    onClear: () -> Unit,
) {
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val hasAlerts = (state as? AlertHistoryUiState.Loaded)?.alerts?.isNotEmpty() == true
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_alert_history)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.settings_back),
                        )
                    }
                },
                actions = {
                    if (hasAlerts) {
                        IconButton(onClick = { confirmClear = true }) {
                            Icon(
                                Icons.Outlined.DeleteSweep,
                                contentDescription = stringResource(R.string.history_clear),
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        when (state) {
            AlertHistoryUiState.Loading -> {
                LoadingState(Modifier.padding(padding))
            }

            is AlertHistoryUiState.Loaded -> {
                if (state.alerts.isEmpty()) {
                    EmptyState(
                        title = stringResource(R.string.history_empty_title),
                        message = stringResource(R.string.history_empty_message),
                        icon = Icons.Outlined.NotificationsNone,
                        modifier = Modifier.padding(padding),
                    )
                } else {
                    LazyColumn(contentPadding = padding) {
                        items(state.alerts, key = { "${it.receivedAtMillis}-${it.monitorId}-${it.status}" }) { alert ->
                            AlertRow(alert, onMonitorClick)
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.history_clear_title)) },
            text = { Text(stringResource(R.string.history_clear_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        onClear()
                    },
                ) { Text(stringResource(R.string.history_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.settings_cancel)) }
            },
        )
    }
}

@Composable
private fun AlertRow(
    alert: AlertRecord,
    onMonitorClick: (Long) -> Unit,
) {
    val locale = currentLocale()
    val monitorId = alert.monitorId
    val title =
        alert.monitorName
            ?: monitorId?.let { stringResource(R.string.history_monitor_fallback, it) }
            ?: stringResource(R.string.history_generic_title)
    val status = alert.status?.toDisplayStatus()
    val statusText = status?.let { stringResource(it.labelRes()) } ?: stringResource(R.string.history_info)
    val mutedText = stringResource(R.string.history_not_notified)
    ListItem(
        overlineContent = {
            Text(formatDateTime(alert.receivedAtMillis, locale) + " · " + statusText)
        },
        headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent =
            alert.message.takeIf { it.isNotBlank() }?.let { message ->
                { Text(message, maxLines = 3, overflow = TextOverflow.Ellipsis) }
            },
        leadingContent = {
            if (status != null) {
                StatusDot(status, announce = false)
            } else {
                Icon(Icons.Outlined.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        trailingContent =
            if (alert.notified) {
                null
            } else {
                {
                    Icon(
                        Icons.Outlined.NotificationsOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.semantics { contentDescription = mutedText },
                    )
                }
            },
        modifier = if (monitorId != null) Modifier.clickable { onMonitorClick(monitorId) } else Modifier,
    )
}

private fun MonitorStatus.toDisplayStatus(): DisplayStatus =
    when (this) {
        MonitorStatus.UP -> DisplayStatus.UP
        MonitorStatus.DOWN -> DisplayStatus.DOWN
        MonitorStatus.PENDING -> DisplayStatus.PENDING
        MonitorStatus.MAINTENANCE -> DisplayStatus.MAINTENANCE
    }

@Preview(showBackground = true)
@Composable
private fun AlertHistoryPreview() {
    val now = 1_761_700_000_000L
    KumaTheme(dynamicColor = false) {
        AlertHistoryContent(
            state =
                AlertHistoryUiState.Loaded(
                    listOf(
                        AlertRecord(now, 2, "Public API", MonitorStatus.DOWN, "Connection refused"),
                        AlertRecord(now - 600_000, 1, "Website", MonitorStatus.UP, "200 - OK", notified = false),
                        AlertRecord(now - 3_600_000, 1, "Website", null, "Certificate expires in 7 days"),
                        AlertRecord(now - 7_200_000, null, null, null, "Test notification"),
                    ),
                ),
            onBack = {},
            onMonitorClick = {},
            onClear = {},
        )
    }
}

@Preview(showBackground = true, locale = "de")
@Composable
private fun AlertHistoryEmptyPreview() {
    KumaTheme(dynamicColor = false) {
        AlertHistoryContent(
            state = AlertHistoryUiState.Loaded(emptyList()),
            onBack = {},
            onMonitorClick = {},
            onClear = {},
        )
    }
}
