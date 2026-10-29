package net.kirkanos.uptimekuma.feature.maintenance.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.kirkanos.uptimekuma.core.model.DisplayStatus
import net.kirkanos.uptimekuma.core.model.KumaError
import net.kirkanos.uptimekuma.core.model.Maintenance
import net.kirkanos.uptimekuma.core.model.MaintenanceStatusPage
import net.kirkanos.uptimekuma.core.ui.component.EmptyState
import net.kirkanos.uptimekuma.core.ui.component.ErrorState
import net.kirkanos.uptimekuma.core.ui.component.KumaErrorText
import net.kirkanos.uptimekuma.core.ui.component.LoadingState
import net.kirkanos.uptimekuma.core.ui.component.StatusDot
import net.kirkanos.uptimekuma.core.ui.component.labelRes
import net.kirkanos.uptimekuma.core.ui.format.currentLocale
import net.kirkanos.uptimekuma.core.ui.format.formatDateTime
import net.kirkanos.uptimekuma.core.ui.format.rememberCurrentTimeMillis
import net.kirkanos.uptimekuma.core.ui.theme.KumaTheme
import net.kirkanos.uptimekuma.feature.maintenance.MaintenanceStatusChip
import net.kirkanos.uptimekuma.feature.maintenance.PreviewData
import net.kirkanos.uptimekuma.feature.maintenance.R
import net.kirkanos.uptimekuma.feature.maintenance.common.ActionResultSnackbar
import net.kirkanos.uptimekuma.feature.maintenance.common.ConfirmActionDialog
import net.kirkanos.uptimekuma.feature.maintenance.effectiveRangeText
import net.kirkanos.uptimekuma.feature.maintenance.list.MaintenanceAction
import net.kirkanos.uptimekuma.feature.maintenance.scheduleSummary
import net.kirkanos.uptimekuma.feature.maintenance.timezoneText
import net.kirkanos.uptimekuma.feature.maintenance.windowText

@Immutable
internal class MaintenanceDetailActions(
    val onBack: () -> Unit = {},
    val onEdit: () -> Unit = {},
    val onClone: () -> Unit = {},
    val onPause: () -> Unit = {},
    val onResume: () -> Unit = {},
    val onDelete: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onMonitorClick: (Long) -> Unit = {},
    val onResultShown: () -> Unit = {},
)

@Composable
internal fun MaintenanceDetailEntry(
    onBack: () -> Unit,
    onEdit: (Long) -> Unit,
    onClone: (Long) -> Unit,
    onMonitorClick: (Long) -> Unit,
    viewModel: MaintenanceDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Runs again when returning from the editor, so changed links show up.
    LaunchedEffect(viewModel) { viewModel.load() }
    LaunchedEffect(state.deleted) { if (state.deleted) onBack() }
    val id = viewModel.maintenanceId
    val actions =
        remember(viewModel, onBack, onEdit, onClone, onMonitorClick) {
            MaintenanceDetailActions(
                onBack = onBack,
                onEdit = { onEdit(id) },
                onClone = { onClone(id) },
                onPause = viewModel::pause,
                onResume = viewModel::resume,
                onDelete = viewModel::delete,
                onRetry = viewModel::load,
                onMonitorClick = onMonitorClick,
                onResultShown = viewModel::consumeActionResult,
            )
        }
    MaintenanceDetailScreen(state = state, actions = actions)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MaintenanceDetailScreen(
    state: MaintenanceDetailUiState,
    actions: MaintenanceDetailActions,
    modifier: Modifier = Modifier,
    nowMillis: Long = rememberCurrentTimeMillis(),
) {
    val snackbarHostState = remember { SnackbarHostState() }
    ActionResultSnackbar(state.actionResult, snackbarHostState, actions.onResultShown)
    var confirm by rememberSaveable { mutableStateOf<MaintenanceAction?>(null) }
    val maintenance = state.maintenance

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(maintenance?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.maintenance_back))
                    }
                },
                actions = {
                    if (maintenance != null) {
                        TopActions(maintenance, state.actionInProgress != null, actions, onConfirm = { confirm = it })
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        val contentModifier = Modifier.padding(padding).fillMaxSize()
        when {
            state.loading || state.deleted -> {
                LoadingState(contentModifier)
            }

            maintenance == null && state.loadError != null -> {
                ErrorState(state.loadError, contentModifier, actions.onRetry)
            }

            maintenance == null -> {
                EmptyState(
                    title = stringResource(R.string.maintenance_not_found),
                    icon = Icons.Outlined.SearchOff,
                    actionLabel = stringResource(R.string.maintenance_back),
                    onAction = actions.onBack,
                    modifier = contentModifier,
                )
            }

            else -> {
                DetailContent(maintenance, state, actions, nowMillis, contentModifier)
            }
        }
    }

    confirm?.let { action ->
        ConfirmActionDialog(
            action = action,
            title = maintenance?.title.orEmpty(),
            onConfirm = {
                confirm = null
                if (action == MaintenanceAction.DELETE) actions.onDelete() else actions.onPause()
            },
            onDismiss = { confirm = null },
        )
    }
}

@Composable
private fun TopActions(
    maintenance: Maintenance,
    busy: Boolean,
    actions: MaintenanceDetailActions,
    onConfirm: (MaintenanceAction) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    when {
        busy -> {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        }

        maintenance.active -> {
            IconButton(onClick = { onConfirm(MaintenanceAction.PAUSE) }) {
                Icon(Icons.Outlined.Pause, stringResource(R.string.maintenance_pause))
            }
        }

        else -> {
            IconButton(onClick = actions.onResume) {
                Icon(Icons.Outlined.PlayArrow, stringResource(R.string.maintenance_resume))
            }
        }
    }
    IconButton(onClick = actions.onEdit) { Icon(Icons.Outlined.Edit, stringResource(R.string.maintenance_edit)) }
    Box {
        IconButton(onClick = { menuOpen = true }) {
            Icon(Icons.Outlined.MoreVert, stringResource(R.string.maintenance_more))
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.maintenance_clone)) },
                leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    actions.onClone()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.maintenance_delete), color = MaterialTheme.colorScheme.error) },
                leadingIcon = {
                    Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                },
                onClick = {
                    menuOpen = false
                    onConfirm(MaintenanceAction.DELETE)
                },
            )
        }
    }
}

@Composable
private fun DetailContent(
    maintenance: Maintenance,
    state: MaintenanceDetailUiState,
    actions: MaintenanceDetailActions,
    nowMillis: Long,
    modifier: Modifier,
) {
    LazyColumn(modifier = modifier, contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "summary") { SummaryCard(maintenance, nowMillis) }
        if (maintenance.description.isNotBlank()) {
            section("description", R.string.maintenance_section_description)
            item(key = "description-text") {
                Text(
                    maintenance.description,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        if (state.loadError != null) {
            item(key = "links-error") {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.maintenance_links_error), style = MaterialTheme.typography.bodyMedium)
                    KumaErrorText(state.loadError, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = actions.onRetry) { Text(stringResource(R.string.maintenance_retry)) }
                }
            }
        }
        monitorsSection(state, actions)
        statusPagesSection(state.statusPages)
    }
}

@Composable
private fun SummaryCard(
    maintenance: Maintenance,
    nowMillis: Long,
) {
    val locale = currentLocale()
    Card(Modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MaintenanceStatusChip(maintenance.status)
            Text(windowText(maintenance, nowMillis), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.maintenance_section_schedule),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp).semantics { heading() },
            )
            Text(scheduleSummary(maintenance), style = MaterialTheme.typography.bodyLarge)
            effectiveRangeText(maintenance)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Text(
                stringResource(R.string.maintenance_timezone_line, timezoneText(maintenance)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val slots = maintenance.timeslots.filter { it.endMillis > nowMillis }
            if (slots.isNotEmpty()) {
                Text(
                    stringResource(R.string.maintenance_section_windows),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp).semantics { heading() },
                )
                slots.forEach { slot ->
                    Text(
                        stringResource(
                            R.string.maintenance_window,
                            formatDateTime(slot.startMillis, locale),
                            formatDateTime(slot.endMillis, locale),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

private fun LazyListScope.section(
    key: String,
    titleRes: Int,
) {
    item(key = "section-$key") {
        Text(
            stringResource(titleRes),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp).semantics { heading() },
        )
    }
}

private fun LazyListScope.monitorsSection(
    state: MaintenanceDetailUiState,
    actions: MaintenanceDetailActions,
) {
    section("monitors", R.string.maintenance_section_monitors)
    if (state.monitors.isEmpty()) {
        item(key = "no-monitors") { EmptyLine(R.string.maintenance_no_monitors) }
        return
    }
    items(state.monitors, key = { "monitor-${it.id}" }) { monitor ->
        val name = monitor.name ?: stringResource(R.string.maintenance_monitor_unknown, monitor.id)
        val openLabel = stringResource(R.string.maintenance_open_monitor, name)
        ListItem(
            modifier = Modifier.clickable(onClickLabel = openLabel) { actions.onMonitorClick(monitor.id) },
            leadingContent = { StatusDot(monitor.status ?: DisplayStatus.UNKNOWN) },
            headlineContent = { Text(name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            supportingContent = monitor.status?.let { status -> { Text(stringResource(status.labelRes())) } },
            trailingContent = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null) },
        )
    }
}

private fun LazyListScope.statusPagesSection(pages: List<MaintenanceStatusPage>) {
    section("pages", R.string.maintenance_section_status_pages)
    if (pages.isEmpty()) {
        item(key = "no-pages") { EmptyLine(R.string.maintenance_no_status_pages) }
        return
    }
    items(pages, key = { "page-${it.id}" }) { page ->
        ListItem(
            leadingContent = { Icon(Icons.Outlined.Public, contentDescription = null) },
            headlineContent = { Text(page.title) },
            supportingContent = page.slug?.let { slug -> { Text("/status/$slug") } },
        )
    }
}

@Composable
private fun EmptyLine(textRes: Int) {
    Text(
        stringResource(textRes),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Preview(showBackground = true, heightDp = 900)
@Preview(showBackground = true, heightDp = 900, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun MaintenanceDetailPreview() {
    KumaTheme(dynamicColor = false) {
        MaintenanceDetailScreen(
            state =
                MaintenanceDetailUiState(
                    loading = false,
                    maintenance = PreviewData.weekly,
                    monitors =
                        listOf(
                            AffectedMonitor(1, "Website", DisplayStatus.UP),
                            AffectedMonitor(2, "Infra / Database", DisplayStatus.MAINTENANCE),
                            AffectedMonitor(99, null, null),
                        ),
                    statusPages = PreviewData.statusPages,
                ),
            actions = MaintenanceDetailActions(),
            nowMillis = PreviewData.NOW,
        )
    }
}

@Preview(showBackground = true, heightDp = 600)
@Composable
private fun MaintenanceDetailErrorPreview() {
    KumaTheme(dynamicColor = false) {
        MaintenanceDetailScreen(
            state =
                MaintenanceDetailUiState(
                    loading = false,
                    maintenance = PreviewData.manual,
                    loadError = KumaError(KumaError.Kind.TIMEOUT),
                ),
            actions = MaintenanceDetailActions(),
            nowMillis = PreviewData.NOW,
        )
    }
}
