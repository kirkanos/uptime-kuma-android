package net.kirkanos.uptimekuma.feature.monitors.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.kirkanos.uptimekuma.core.model.AppUpdate
import net.kirkanos.uptimekuma.core.model.ConnectionState
import net.kirkanos.uptimekuma.core.model.DisplayStatus
import net.kirkanos.uptimekuma.core.model.KumaError
import net.kirkanos.uptimekuma.core.model.MonitorTag
import net.kirkanos.uptimekuma.core.ui.component.EmptyState
import net.kirkanos.uptimekuma.core.ui.component.HeartbeatBar
import net.kirkanos.uptimekuma.core.ui.component.LoadingState
import net.kirkanos.uptimekuma.core.ui.component.StatusDot
import net.kirkanos.uptimekuma.core.ui.component.UptimeBadge
import net.kirkanos.uptimekuma.core.ui.component.labelRes
import net.kirkanos.uptimekuma.core.ui.component.localizedMessage
import net.kirkanos.uptimekuma.core.ui.component.parseHexColor
import net.kirkanos.uptimekuma.core.ui.format.pingText
import net.kirkanos.uptimekuma.core.ui.format.relativeTimeText
import net.kirkanos.uptimekuma.core.ui.format.rememberCurrentTimeMillis
import net.kirkanos.uptimekuma.core.ui.theme.KumaTheme
import net.kirkanos.uptimekuma.feature.monitors.PreviewData
import net.kirkanos.uptimekuma.feature.monitors.R
import net.kirkanos.uptimekuma.feature.monitors.monitorTypeLabel

private const val ROW_BEATS = 30
private val FILTER_STATUSES =
    listOf(
        DisplayStatus.DOWN,
        DisplayStatus.PENDING,
        DisplayStatus.MAINTENANCE,
        DisplayStatus.UP,
        DisplayStatus.PAUSED,
        DisplayStatus.UNKNOWN,
    )

/** Callbacks of [DashboardScreen], bundled to keep the composable signature short. */
@Suppress("LongParameterList")
@Immutable
internal class DashboardActions(
    val onQueryChange: (String) -> Unit = {},
    val onToggleStatus: (DisplayStatus) -> Unit = {},
    val onToggleTag: (Long) -> Unit = {},
    val onToggleProblemsFirst: () -> Unit = {},
    val onToggleGroup: (Long) -> Unit = {},
    val onClearFilters: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onDismissRefreshError: () -> Unit = {},
    val onMonitorClick: (Long) -> Unit = {},
    val onSettingsClick: () -> Unit = {},
    val onReLogin: () -> Unit = {},
    val onAddMonitor: () -> Unit = {},
    val onManageTags: () -> Unit = {},
    val onDismissAppUpdate: (String) -> Unit = {},
)

@Composable
internal fun DashboardEntry(
    onMonitorClick: (Long) -> Unit,
    onSettingsClick: () -> Unit,
    onReLogin: () -> Unit,
    onAddMonitor: () -> Unit = {},
    onManageTags: () -> Unit = {},
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val actions =
        remember(viewModel, onMonitorClick, onSettingsClick, onReLogin, onAddMonitor, onManageTags) {
            DashboardActions(
                onQueryChange = viewModel::onQueryChange,
                onToggleStatus = viewModel::toggleStatus,
                onToggleTag = viewModel::toggleTag,
                onToggleProblemsFirst = viewModel::toggleProblemsFirst,
                onToggleGroup = viewModel::toggleGroup,
                onClearFilters = viewModel::clearFilters,
                onRefresh = viewModel::refresh,
                onDismissRefreshError = viewModel::dismissRefreshError,
                onMonitorClick = onMonitorClick,
                onSettingsClick = onSettingsClick,
                onReLogin = onReLogin,
                onAddMonitor = onAddMonitor,
                onManageTags = onManageTags,
                onDismissAppUpdate = viewModel::dismissAppUpdate,
            )
        }
    DashboardScreen(state = state, actions = actions)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DashboardScreen(
    state: DashboardUiState,
    actions: DashboardActions,
    modifier: Modifier = Modifier,
    nowMillis: Long = rememberCurrentTimeMillis(),
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val refreshError = state.refreshError?.localizedMessage()
    LaunchedEffect(refreshError) {
        if (refreshError != null) {
            snackbarHostState.showSnackbar(refreshError)
            actions.onDismissRefreshError()
        }
    }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.monitors_title)) },
                actions = {
                    IconButton(onClick = actions.onSettingsClick) {
                        Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.monitors_settings))
                    }
                    DashboardOverflow(onManageTags = actions.onManageTags)
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = actions.onAddMonitor,
                icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.editor_add_monitor)) },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = actions.onRefresh,
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            if (state.loading) {
                LoadingState()
            } else {
                MonitorListContent(state = state, actions = actions, nowMillis = nowMillis)
            }
        }
    }
}

@Composable
private fun MonitorListContent(
    state: DashboardUiState,
    actions: DashboardActions,
    nowMillis: Long,
) {
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
        // One item for all banners: a banner appearing later grows the first item instead of being inserted
        // above the scroll anchor, where it would stay out of sight.
        item(key = "banners", contentType = "banners") {
            Column {
                ConnectionBanner(state.connection, onRetry = actions.onRefresh, onReLogin = actions.onReLogin)
                state.appUpdate?.let { update ->
                    AppUpdateBanner(update, onDismiss = { actions.onDismissAppUpdate(update.version) })
                }
            }
        }
        item(key = "summary", contentType = "summary") { SummaryHeader(state.counts) }
        item(key = "search", contentType = "search") {
            SearchField(query = state.filter.query, onQueryChange = actions.onQueryChange)
        }
        item(key = "statusFilters", contentType = "filters") {
            StatusFilterRow(
                counts = state.counts,
                filter = state.filter,
                onToggleStatus = actions.onToggleStatus,
                onToggleProblemsFirst = actions.onToggleProblemsFirst,
            )
        }
        if (state.tags.isNotEmpty()) {
            item(key = "tagFilters", contentType = "filters") {
                TagFilterRow(tags = state.tags, selected = state.filter.tagIds, onToggleTag = actions.onToggleTag)
            }
        }
        if (state.rows.isEmpty()) {
            item(key = "empty", contentType = "empty") {
                if (state.filter.isFiltering) {
                    EmptyState(
                        title = stringResource(R.string.monitors_empty_filtered_title),
                        message = stringResource(R.string.monitors_empty_filtered_message),
                        icon = Icons.Outlined.SearchOff,
                        actionLabel = stringResource(R.string.monitors_clear_filters),
                        onAction = actions.onClearFilters,
                    )
                } else {
                    EmptyState(
                        title = stringResource(R.string.monitors_empty_title),
                        message = stringResource(R.string.monitors_empty_message),
                    )
                }
            }
        }
        items(state.rows, key = { it.id }, contentType = { "monitor" }) { row ->
            MonitorRowItem(
                row = row,
                nowMillis = nowMillis,
                onClick = actions.onMonitorClick,
                onToggleGroup = actions.onToggleGroup,
            )
        }
    }
}

@Composable
private fun DashboardOverflow(onManageTags: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.monitors_more_actions))
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.tags_manage)) },
            leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Label, contentDescription = null) },
            onClick = {
                open = false
                onManageTags()
            },
        )
    }
}

@Composable
private fun ConnectionBanner(
    connection: ConnectionState,
    onRetry: () -> Unit,
    onReLogin: () -> Unit,
) {
    when (connection) {
        ConnectionState.Connecting -> {
            Banner(
                text = stringResource(R.string.monitors_connecting),
                icon = null,
                isError = false,
            )
        }

        is ConnectionState.Failed -> {
            Banner(
                text = stringResource(R.string.monitors_connection_failed, connection.error.localizedMessage()),
                icon = Icons.Outlined.CloudOff,
                isError = true,
                actionLabel = stringResource(R.string.monitors_retry),
                onAction = onRetry,
            )
        }

        ConnectionState.LoginRequired -> {
            Banner(
                text = stringResource(R.string.monitors_login_required),
                icon = Icons.Outlined.Lock,
                isError = true,
                actionLabel = stringResource(R.string.monitors_log_in),
                onAction = onReLogin,
            )
        }

        is ConnectionState.Connected, ConnectionState.Disconnected, ConnectionState.NotConfigured -> {
            // Normal states: no banner.
        }
    }
}

@Composable
private fun AppUpdateBanner(
    update: AppUpdate,
    onDismiss: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.NewReleases, contentDescription = null, modifier = Modifier.size(20.dp))
                Text(
                    text = stringResource(R.string.monitors_app_update, update.version),
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.monitors_app_update_later), color = LocalContentColor.current)
                }
                TextButton(onClick = { uriHandler.openUri(update.url) }) {
                    Text(stringResource(R.string.monitors_app_update_open), color = LocalContentColor.current)
                }
            }
        }
    }
}

@Composable
private fun Banner(
    text: String,
    icon: ImageVector?,
    isError: Boolean,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val container =
        if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
    val content =
        if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(12.dp),
        color = container,
        contentColor = content,
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon == null) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = content)
            } else {
                Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            }
            Text(
                text = text,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction) { Text(actionLabel, color = content) }
            }
        }
    }
}

@Composable
private fun SummaryHeader(counts: StatusCounts) {
    val worst =
        when {
            counts[DisplayStatus.DOWN] > 0 -> DisplayStatus.DOWN
            counts[DisplayStatus.PENDING] > 0 -> DisplayStatus.PENDING
            counts[DisplayStatus.MAINTENANCE] > 0 -> DisplayStatus.MAINTENANCE
            counts.total == 0 -> DisplayStatus.UNKNOWN
            else -> DisplayStatus.UP
        }
    val headline =
        when (worst) {
            DisplayStatus.DOWN -> {
                pluralStringResource(R.plurals.monitors_summary_down, counts[worst], counts[worst])
            }

            DisplayStatus.PENDING -> {
                pluralStringResource(R.plurals.monitors_summary_pending, counts[worst], counts[worst])
            }

            DisplayStatus.MAINTENANCE -> {
                pluralStringResource(R.plurals.monitors_summary_maintenance, counts[worst], counts[worst])
            }

            else -> {
                stringResource(R.string.monitors_summary_all_up)
            }
        }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(status = worst, size = 16.dp, announce = false)
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(text = headline, style = MaterialTheme.typography.titleLarge)
            Text(
                text = pluralStringResource(R.plurals.monitors_summary_total, counts.total, counts.total),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        placeholder = { Text(stringResource(R.string.monitors_search_hint)) },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Outlined.Clear, contentDescription = stringResource(R.string.monitors_search_clear))
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
    )
}

@Composable
private fun StatusFilterRow(
    counts: StatusCounts,
    filter: DashboardFilter,
    onToggleStatus: (DisplayStatus) -> Unit,
    onToggleProblemsFirst: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            selected = filter.problemsFirst,
            onClick = onToggleProblemsFirst,
            label = { Text(stringResource(R.string.monitors_problems_first)) },
            leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Sort, contentDescription = null) },
        )
        VerticalDivider(modifier = Modifier.height(24.dp))
        FILTER_STATUSES.forEach { status ->
            val count = counts[status]
            val selected = status in filter.statuses
            if (count > 0 || selected) {
                FilterChip(
                    selected = selected,
                    onClick = { onToggleStatus(status) },
                    label = {
                        Text(
                            stringResource(R.string.monitors_status_count, count, stringResource(status.labelRes())),
                        )
                    },
                    leadingIcon = { StatusDot(status = status, size = 10.dp, announce = false) },
                )
            }
        }
    }
}

@Composable
private fun TagFilterRow(
    tags: List<MonitorTag>,
    selected: Set<Long>,
    onToggleTag: (Long) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tags.forEach { tag ->
            val color = remember(tag.color) { parseHexColor(tag.color) } ?: MaterialTheme.colorScheme.secondary
            FilterChip(
                selected = tag.tagId in selected,
                onClick = { onToggleTag(tag.tagId) },
                label = { Text(tag.name) },
                leadingIcon = { Box(Modifier.size(10.dp).background(color, CircleShape)) },
            )
        }
    }
}

@Composable
private fun MonitorRowItem(
    row: MonitorRow,
    nowMillis: Long,
    onClick: (Long) -> Unit,
    onToggleGroup: (Long) -> Unit,
) {
    val overview = row.overview
    val monitor = overview.monitor
    val status = overview.displayStatus
    val details =
        buildList {
            add(monitor.target ?: monitorTypeLabel(monitor.type))
            overview.lastBeat?.pingMs?.let { add(pingText(it)) }
            overview.lastBeat?.let { add(relativeTimeText(it.timeMillis, nowMillis)) }
        }.joinToString(" · ")
    val openLabel = stringResource(R.string.monitors_open_details)
    Column {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(onClickLabel = openLabel) { onClick(monitor.id) }
                    .padding(start = 16.dp + 20.dp * row.depth, end = 8.dp, top = 10.dp, bottom = 10.dp)
                    .alpha(if (status == DisplayStatus.PAUSED) 0.6f else 1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(status = status)
            Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    text =
                        if (row.childCount > 0) {
                            stringResource(R.string.monitors_group_name, monitor.name, row.childCount)
                        } else {
                            monitor.name
                        },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = details,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                HeartbeatBar(
                    beats = overview.recentBeats,
                    maxBars = ROW_BEATS,
                    height = 14.dp,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
            }
            UptimeBadge(fraction = overview.uptime?.uptime24h)
            if (row.childCount > 0) {
                IconButton(onClick = { onToggleGroup(monitor.id) }) {
                    Icon(
                        imageVector = if (row.expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription =
                            stringResource(
                                if (row.expanded) R.string.monitors_collapse_group else R.string.monitors_expand_group,
                                monitor.name,
                            ),
                    )
                }
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = 16.dp + 20.dp * row.depth),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        )
    }
}

private fun previewState(
    connection: ConnectionState =
        ConnectionState.Connected(
            net.kirkanos.uptimekuma.core.model
                .ServerInfo("2.5.8"),
        ),
    filter: DashboardFilter = DashboardFilter(),
) = DashboardUiState(
    loading = false,
    rows = MonitorList.rows(PreviewData.monitors, filter),
    counts = MonitorList.counts(PreviewData.monitors),
    tags = MonitorList.tags(PreviewData.monitors),
    filter = filter,
    connection = connection,
)

@Preview(showBackground = true, heightDp = 800)
@Composable
private fun DashboardPreview() {
    KumaTheme(dynamicColor = false) {
        DashboardScreen(state = previewState(), actions = DashboardActions(), nowMillis = PreviewData.NOW)
    }
}

@Preview(showBackground = true, heightDp = 800, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun DashboardDarkFailedPreview() {
    KumaTheme(dynamicColor = false) {
        DashboardScreen(
            state =
                previewState(
                    connection = ConnectionState.Failed(KumaError(KumaError.Kind.NETWORK)),
                    filter = DashboardFilter(problemsFirst = true),
                ),
            actions = DashboardActions(),
            nowMillis = PreviewData.NOW,
        )
    }
}

@Preview(showBackground = true, heightDp = 800, fontScale = 1.8f)
@Composable
private fun DashboardLargeFontPreview() {
    KumaTheme(dynamicColor = false) {
        DashboardScreen(
            state = previewState(connection = ConnectionState.LoginRequired),
            actions = DashboardActions(),
            nowMillis = PreviewData.NOW,
        )
    }
}

@Preview(showBackground = true, heightDp = 600)
@Composable
private fun DashboardEmptyFilterPreview() {
    KumaTheme(dynamicColor = false) {
        DashboardScreen(
            state = previewState(filter = DashboardFilter(query = "nothing matches")),
            actions = DashboardActions(),
            nowMillis = PreviewData.NOW,
        )
    }
}
