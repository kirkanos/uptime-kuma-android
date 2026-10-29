package net.kirkanos.uptimekuma.feature.maintenance.list

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EventBusy
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import net.kirkanos.uptimekuma.core.model.KumaError
import net.kirkanos.uptimekuma.core.model.Maintenance
import net.kirkanos.uptimekuma.core.ui.component.EmptyState
import net.kirkanos.uptimekuma.core.ui.component.ErrorState
import net.kirkanos.uptimekuma.core.ui.component.LoadingState
import net.kirkanos.uptimekuma.core.ui.format.rememberCurrentTimeMillis
import net.kirkanos.uptimekuma.core.ui.theme.KumaTheme
import net.kirkanos.uptimekuma.feature.maintenance.MaintenanceStatusChip
import net.kirkanos.uptimekuma.feature.maintenance.PreviewData
import net.kirkanos.uptimekuma.feature.maintenance.R
import net.kirkanos.uptimekuma.feature.maintenance.common.ActionResultSnackbar
import net.kirkanos.uptimekuma.feature.maintenance.common.ConfirmActionDialog
import net.kirkanos.uptimekuma.feature.maintenance.titleRes
import net.kirkanos.uptimekuma.feature.maintenance.windowText

@Immutable
internal class MaintenanceListActions(
    val onBack: () -> Unit = {},
    val onOpen: (Long) -> Unit = {},
    val onAdd: () -> Unit = {},
    val onEdit: (Long) -> Unit = {},
    val onClone: (Long) -> Unit = {},
    /** Pause, resume or delete the maintenance with the given id. */
    val onAction: (MaintenanceAction, Long) -> Unit = { _, _ -> },
    val onRefresh: () -> Unit = {},
    val onResultShown: () -> Unit = {},
)

@Composable
internal fun MaintenanceListEntry(
    onBack: () -> Unit,
    onOpen: (Long) -> Unit,
    onAdd: () -> Unit,
    onEdit: (Long) -> Unit,
    onClone: (Long) -> Unit,
    viewModel: MaintenanceListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val actions =
        remember(viewModel, onBack, onOpen, onAdd, onEdit, onClone) {
            MaintenanceListActions(
                onBack = onBack,
                onOpen = onOpen,
                onAdd = onAdd,
                onEdit = onEdit,
                onClone = onClone,
                onAction = viewModel::perform,
                onRefresh = viewModel::refresh,
                onResultShown = viewModel::consumeActionResult,
            )
        }
    MaintenanceListScreen(state = state, actions = actions)
}

private data class PendingConfirm(
    val action: MaintenanceAction,
    val id: Long,
    val title: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MaintenanceListScreen(
    state: MaintenanceListUiState,
    actions: MaintenanceListActions,
    modifier: Modifier = Modifier,
    nowMillis: Long = rememberCurrentTimeMillis(),
) {
    val snackbarHostState = remember { SnackbarHostState() }
    ActionResultSnackbar(state.actionResult, snackbarHostState, actions.onResultShown)
    var confirm by remember { mutableStateOf<PendingConfirm?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.maintenance_title)) },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.maintenance_back))
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = actions.onAdd,
                icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.maintenance_add)) },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        val contentModifier = Modifier.padding(padding).fillMaxSize()
        val groups = state.groups
        when {
            state.loading -> {
                LoadingState(contentModifier)
            }

            groups == null -> {
                ErrorState(state.loadError ?: KumaError(KumaError.Kind.UNKNOWN), contentModifier, actions.onRefresh)
            }

            else -> {
                PullToRefreshBox(
                    isRefreshing = state.refreshing,
                    onRefresh = actions.onRefresh,
                    modifier = contentModifier,
                ) {
                    if (groups.isEmpty()) {
                        EmptyState(
                            title = stringResource(R.string.maintenance_empty_title),
                            message = stringResource(R.string.maintenance_empty_message),
                            icon = Icons.Outlined.EventBusy,
                            actionLabel = stringResource(R.string.maintenance_add),
                            onAction = actions.onAdd,
                        )
                    } else {
                        MaintenanceList(
                            groups = groups,
                            busyIds = state.busyIds,
                            nowMillis = nowMillis,
                            actions = actions,
                            onConfirm = { confirm = it },
                        )
                    }
                }
            }
        }
    }

    confirm?.let { pending ->
        ConfirmActionDialog(
            action = pending.action,
            title = pending.title,
            onConfirm = {
                confirm = null
                actions.onAction(pending.action, pending.id)
            },
            onDismiss = { confirm = null },
        )
    }
}

@Composable
private fun MaintenanceList(
    groups: List<Pair<MaintenanceGroup, List<Maintenance>>>,
    busyIds: Set<Long>,
    nowMillis: Long,
    actions: MaintenanceListActions,
    onConfirm: (PendingConfirm) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 96.dp),
    ) {
        groups.forEach { (group, items) ->
            item(key = "header-${group.name}") {
                Text(
                    text = stringResource(group.titleRes()),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier =
                        Modifier
                            .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp)
                            .semantics { heading() },
                )
            }
            items(items, key = { it.id }) { maintenance ->
                MaintenanceRow(
                    maintenance = maintenance,
                    busy = maintenance.id in busyIds,
                    nowMillis = nowMillis,
                    actions = actions,
                    onConfirm = onConfirm,
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun MaintenanceRow(
    maintenance: Maintenance,
    busy: Boolean,
    nowMillis: Long,
    actions: MaintenanceListActions,
    onConfirm: (PendingConfirm) -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable { actions.onOpen(maintenance.id) },
        headlineContent = { Text(maintenance.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    MaintenanceStatusChip(maintenance.status)
                }
                Text(windowText(maintenance, nowMillis), style = MaterialTheme.typography.bodyMedium)
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (busy) {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                } else if (maintenance.active) {
                    IconButton(
                        onClick = {
                            onConfirm(
                                PendingConfirm(MaintenanceAction.PAUSE, maintenance.id, maintenance.title),
                            )
                        },
                    ) {
                        Icon(Icons.Outlined.Pause, stringResource(R.string.maintenance_pause_named, maintenance.title))
                    }
                } else {
                    IconButton(onClick = { actions.onAction(MaintenanceAction.RESUME, maintenance.id) }) {
                        Icon(
                            Icons.Outlined.PlayArrow,
                            stringResource(R.string.maintenance_resume_named, maintenance.title),
                        )
                    }
                }
                RowMenu(maintenance, actions, onConfirm)
            }
        },
    )
}

@Composable
private fun RowMenu(
    maintenance: Maintenance,
    actions: MaintenanceListActions,
    onConfirm: (PendingConfirm) -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Outlined.MoreVert, stringResource(R.string.maintenance_more))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.maintenance_edit)) },
                leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                onClick = {
                    open = false
                    actions.onEdit(maintenance.id)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.maintenance_clone)) },
                leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                onClick = {
                    open = false
                    actions.onClone(maintenance.id)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.maintenance_delete), color = MaterialTheme.colorScheme.error) },
                leadingIcon = {
                    Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                },
                onClick = {
                    open = false
                    onConfirm(PendingConfirm(MaintenanceAction.DELETE, maintenance.id, maintenance.title))
                },
            )
        }
    }
}

@Preview(showBackground = true, heightDp = 900)
@Preview(showBackground = true, heightDp = 900, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun MaintenanceListPreview() {
    KumaTheme(dynamicColor = false) {
        MaintenanceListScreen(
            state = MaintenanceListUiState(groups = MaintenanceGroup.group(PreviewData.all), busyIds = setOf(3L)),
            actions = MaintenanceListActions(),
            nowMillis = PreviewData.NOW,
        )
    }
}

@Preview(showBackground = true, heightDp = 400)
@Composable
private fun MaintenanceListEmptyPreview() {
    KumaTheme(dynamicColor = false) {
        MaintenanceListScreen(
            state = MaintenanceListUiState(groups = emptyList()),
            actions = MaintenanceListActions(),
            nowMillis = PreviewData.NOW,
        )
    }
}
