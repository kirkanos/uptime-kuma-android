package net.kirkanos.uptimekuma.feature.monitors.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import net.kirkanos.uptimekuma.core.model.CertInfo
import net.kirkanos.uptimekuma.core.model.DisplayStatus
import net.kirkanos.uptimekuma.core.model.Heartbeat
import net.kirkanos.uptimekuma.core.model.KumaError
import net.kirkanos.uptimekuma.core.model.MonitorOverview
import net.kirkanos.uptimekuma.core.model.MonitorStatus
import net.kirkanos.uptimekuma.core.ui.component.EmptyState
import net.kirkanos.uptimekuma.core.ui.component.HeartbeatBar
import net.kirkanos.uptimekuma.core.ui.component.KumaErrorText
import net.kirkanos.uptimekuma.core.ui.component.LoadingState
import net.kirkanos.uptimekuma.core.ui.component.PingChart
import net.kirkanos.uptimekuma.core.ui.component.StatusChip
import net.kirkanos.uptimekuma.core.ui.component.StatusDot
import net.kirkanos.uptimekuma.core.ui.component.TagChip
import net.kirkanos.uptimekuma.core.ui.component.labelRes
import net.kirkanos.uptimekuma.core.ui.component.localizedMessage
import net.kirkanos.uptimekuma.core.ui.format.currentLocale
import net.kirkanos.uptimekuma.core.ui.format.formatDate
import net.kirkanos.uptimekuma.core.ui.format.formatDateTime
import net.kirkanos.uptimekuma.core.ui.format.formatUptimePercent
import net.kirkanos.uptimekuma.core.ui.format.pingText
import net.kirkanos.uptimekuma.core.ui.format.relativeTimeText
import net.kirkanos.uptimekuma.core.ui.format.rememberCurrentTimeMillis
import net.kirkanos.uptimekuma.core.ui.theme.KumaTheme
import net.kirkanos.uptimekuma.core.ui.theme.statusColors
import net.kirkanos.uptimekuma.feature.monitors.LocalMonitorAlertSettings
import net.kirkanos.uptimekuma.feature.monitors.PreviewData
import net.kirkanos.uptimekuma.feature.monitors.R
import net.kirkanos.uptimekuma.feature.monitors.isWebUrl
import net.kirkanos.uptimekuma.feature.monitors.monitorTypeLabel

private const val CERT_WARNING_DAYS = 14
private const val LOAD_MORE_THRESHOLD = 3
private const val SECONDS_PER_MINUTE = 60
private const val SECONDS_PER_HOUR = 3_600

/** Callbacks of [MonitorDetailScreen]. */
@Suppress("LongParameterList")
@Immutable
internal class MonitorDetailActions(
    val onBack: () -> Unit = {},
    val onSelectPeriod: (ChartPeriod) -> Unit = {},
    val onRetryChart: () -> Unit = {},
    val onLoadMoreEvents: () -> Unit = {},
    val onPause: () -> Unit = {},
    val onResume: () -> Unit = {},
    val onDelete: () -> Unit = {},
    val onDismissActionError: () -> Unit = {},
    val onEdit: () -> Unit = {},
    val onClone: () -> Unit = {},
)

@Composable
internal fun MonitorDetailEntry(
    onBack: () -> Unit,
    onEdit: (Long) -> Unit = {},
    onClone: (Long) -> Unit = {},
    viewModel: MonitorDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.deleted) {
        if (state.deleted) onBack()
    }
    val actions =
        remember(viewModel, onBack, onEdit, onClone) {
            MonitorDetailActions(
                onBack = onBack,
                onSelectPeriod = viewModel::selectPeriod,
                onRetryChart = viewModel::retryChart,
                onLoadMoreEvents = viewModel::loadMoreEvents,
                onPause = viewModel::pause,
                onResume = viewModel::resume,
                onDelete = viewModel::delete,
                onDismissActionError = viewModel::dismissActionError,
                onEdit = { onEdit(viewModel.monitorId) },
                onClone = { onClone(viewModel.monitorId) },
            )
        }
    MonitorDetailScreen(state = state, actions = actions)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MonitorDetailScreen(
    state: MonitorDetailUiState,
    actions: MonitorDetailActions,
    modifier: Modifier = Modifier,
    nowMillis: Long = rememberCurrentTimeMillis(),
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val actionError = state.actionError?.localizedMessage()
    LaunchedEffect(actionError) {
        if (actionError != null) {
            snackbarHostState.showSnackbar(actionError)
            actions.onDismissActionError()
        }
    }
    var confirm by rememberSaveable { mutableStateOf<MonitorAction?>(null) }
    val overview = state.overview

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = overview?.monitor?.name.orEmpty(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.monitors_back),
                        )
                    }
                },
                actions = {
                    if (overview != null) {
                        DetailActions(
                            state = state,
                            onPause = { confirm = MonitorAction.PAUSE },
                            onResume = actions.onResume,
                            onDelete = { confirm = MonitorAction.DELETE },
                            onEdit = actions.onEdit,
                            onClone = actions.onClone,
                        )
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

            overview == null -> {
                EmptyState(
                    title = stringResource(R.string.monitors_not_found),
                    icon = Icons.Outlined.SearchOff,
                    modifier = contentModifier,
                    actionLabel = stringResource(R.string.monitors_back),
                    onAction = actions.onBack,
                )
            }

            else -> {
                DetailContent(
                    overview = overview,
                    state = state,
                    actions = actions,
                    nowMillis = nowMillis,
                    modifier = contentModifier,
                )
            }
        }
    }

    confirm?.let { action ->
        ConfirmDialog(
            action = action,
            name = overview?.monitor?.name.orEmpty(),
            onConfirm = {
                confirm = null
                if (action == MonitorAction.DELETE) actions.onDelete() else actions.onPause()
            },
            onDismiss = { confirm = null },
        )
    }
}

@Composable
private fun DetailActions(
    state: MonitorDetailUiState,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit = {},
    onClone: () -> Unit = {},
) {
    val overview = state.overview ?: return
    val uriHandler = LocalUriHandler.current
    var menuOpen by remember { mutableStateOf(false) }
    val busy = state.actionInProgress != null
    if (busy) {
        CircularProgressIndicator(modifier = Modifier.padding(12.dp).height(24.dp), strokeWidth = 2.dp)
    } else if (overview.monitor.active) {
        IconButton(onClick = onPause) {
            Icon(Icons.Outlined.Pause, contentDescription = stringResource(R.string.monitors_pause))
        }
    } else {
        IconButton(onClick = onResume) {
            Icon(Icons.Outlined.PlayArrow, contentDescription = stringResource(R.string.monitors_resume))
        }
    }
    IconButton(onClick = { menuOpen = true }) {
        Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.monitors_more_actions))
    }
    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.editor_edit)) },
            leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
            enabled = !busy,
            onClick = {
                menuOpen = false
                onEdit()
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.editor_clone)) },
            leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
            enabled = !busy,
            onClick = {
                menuOpen = false
                onClone()
            },
        )
        state.webUrl?.let { url ->
            DropdownMenuItem(
                text = { Text(stringResource(R.string.monitors_open_web_ui)) },
                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    runCatching { uriHandler.openUri(url) }
                },
            )
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.monitors_delete)) },
            leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
            enabled = !busy,
            onClick = {
                menuOpen = false
                onDelete()
            },
        )
    }
}

@Composable
private fun ConfirmDialog(
    action: MonitorAction,
    name: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val delete = action == MonitorAction.DELETE
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(if (delete) Icons.Outlined.Delete else Icons.Outlined.Pause, contentDescription = null) },
        title = {
            Text(stringResource(if (delete) R.string.monitors_delete_title else R.string.monitors_pause_title, name))
        },
        text = {
            Text(stringResource(if (delete) R.string.monitors_delete_message else R.string.monitors_pause_message))
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(if (delete) R.string.monitors_delete else R.string.monitors_pause),
                    color = if (delete) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.monitors_cancel)) } },
    )
}

@Composable
private fun DetailContent(
    overview: MonitorOverview,
    state: MonitorDetailUiState,
    actions: MonitorDetailActions,
    nowMillis: Long,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    InfiniteScroll(listState, enabled = !state.events.endReached, onLoadMore = actions.onLoadMoreEvents)
    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "header") { Header(overview, nowMillis) }
        item(key = "alerts") { LocalMonitorAlertSettings.current(overview.monitor.id) }
        item(key = "beats") { BeatsCard(overview) }
        item(key = "stats") { StatsGrid(overview) }
        item(key = "chart") {
            ChartCard(state.chart, onSelect = actions.onSelectPeriod, onRetry = actions.onRetryChart)
        }
        overview.cert?.let { cert -> item(key = "cert") { CertCard(cert) } }
        item(key = "eventsHeader") {
            Text(
                text = stringResource(R.string.monitors_events_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp).semantics { heading() },
            )
        }
        items(state.events.items, key = { "event-${it.timeMillis}-${it.status}" }, contentType = { "event" }) {
            EventRow(beat = it, nowMillis = nowMillis)
        }
        item(key = "eventsFooter") { EventsFooter(state.events, onRetry = actions.onLoadMoreEvents) }
    }
}

@Composable
private fun InfiniteScroll(
    listState: LazyListState,
    enabled: Boolean,
    onLoadMore: () -> Unit,
) {
    LaunchedEffect(listState, enabled) {
        if (!enabled) return@LaunchedEffect
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - LOAD_MORE_THRESHOLD
        }.distinctUntilChanged()
            .filter { it }
            .collect { onLoadMore() }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Header(
    overview: MonitorOverview,
    nowMillis: Long,
) {
    val monitor = overview.monitor
    val uriHandler = LocalUriHandler.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusChip(overview.displayStatus)
            Text(
                text = "${monitorTypeLabel(monitor.type)} · ${intervalText(monitor.intervalSeconds)}",
                modifier = Modifier.padding(start = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        monitor.target?.let { target ->
            if (isWebUrl(target)) {
                val linkColor = MaterialTheme.colorScheme.primary
                val text =
                    remember(target, linkColor) {
                        buildAnnotatedString {
                            withLink(
                                LinkAnnotation.Url(
                                    url = target,
                                    styles =
                                        TextLinkStyles(
                                            SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
                                        ),
                                    linkInteractionListener = { runCatching { uriHandler.openUri(target) } },
                                ),
                            ) { append(target) }
                        }
                    }
                Text(text = text, style = MaterialTheme.typography.bodyLarge)
            } else {
                Text(text = target, style = MaterialTheme.typography.bodyLarge)
            }
        }
        monitor.description?.takeIf { it.isNotBlank() }?.let {
            Text(text = it, style = MaterialTheme.typography.bodyMedium)
        }
        if (monitor.tags.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                monitor.tags.forEach { TagChip(it) }
            }
        }
        overview.lastBeat?.let { beat ->
            val message = beat.message.takeIf { it.isNotBlank() }
            Text(
                text =
                    listOfNotNull(
                        stringResource(R.string.monitors_last_check, relativeTimeText(beat.timeMillis, nowMillis)),
                        message,
                    ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun intervalText(seconds: Int): String =
    when {
        seconds >= SECONDS_PER_HOUR && seconds % SECONDS_PER_HOUR == 0 -> {
            val hours = seconds / SECONDS_PER_HOUR
            pluralStringResource(R.plurals.monitors_interval_hours, hours, hours)
        }

        seconds >= SECONDS_PER_MINUTE && seconds % SECONDS_PER_MINUTE == 0 -> {
            val minutes = seconds / SECONDS_PER_MINUTE
            pluralStringResource(R.plurals.monitors_interval_minutes, minutes, minutes)
        }

        else -> {
            pluralStringResource(R.plurals.monitors_interval_seconds, seconds, seconds)
        }
    }

@Composable
private fun SectionCard(
    title: String?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() },
                )
            }
            content()
        }
    }
}

@Composable
private fun BeatsCard(overview: MonitorOverview) {
    SectionCard(title = stringResource(R.string.monitors_recent_checks)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val maxBars = (maxWidth / 10.dp).toInt().coerceIn(10, 100)
            HeartbeatBar(
                beats = overview.recentBeats,
                maxBars = maxBars,
                height = 32.dp,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatsGrid(overview: MonitorOverview) {
    val uptime = overview.uptime
    val locale = currentLocale()
    val none = stringResource(R.string.monitors_value_none)

    fun percent(value: Double?) = value?.let { formatUptimePercent(it, locale) } ?: none
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        maxItemsInEachRow = 2,
    ) {
        StatCard(stringResource(R.string.monitors_uptime_24h), percent(uptime?.uptime24h), Modifier.weight(1f))
        StatCard(stringResource(R.string.monitors_uptime_30d), percent(uptime?.uptime30d), Modifier.weight(1f))
        StatCard(stringResource(R.string.monitors_uptime_1y), percent(uptime?.uptime1y), Modifier.weight(1f))
        StatCard(
            stringResource(R.string.monitors_avg_ping),
            uptime?.avgPingMs?.let { pingText(it) } ?: none,
            Modifier.weight(1f),
        )
    }
}

@Composable
private fun StatCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.semantics(mergeDescendants = true) {},
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(text = value, style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun ChartCard(
    chart: ChartState,
    onSelect: (ChartPeriod) -> Unit,
    onRetry: () -> Unit,
) {
    SectionCard(title = stringResource(R.string.monitors_response_time)) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            ChartPeriod.entries.forEachIndexed { index, period ->
                SegmentedButton(
                    selected = chart.period == period,
                    onClick = { onSelect(period) },
                    shape = SegmentedButtonDefaults.itemShape(index, ChartPeriod.entries.size),
                    label = { Text(periodLabel(period), maxLines = 1) },
                )
            }
        }
        when {
            chart.loading -> {
                LoadingState(Modifier.fillMaxWidth().height(180.dp))
            }

            chart.error != null -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    KumaErrorText(chart.error)
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.monitors_retry)) }
                }
            }

            chart.points.none { it.pingMs != null } -> {
                Text(
                    text = stringResource(R.string.monitors_chart_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> {
                PingChart(points = chart.points, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun periodLabel(period: ChartPeriod): String =
    when (period) {
        ChartPeriod.H3 -> stringResource(R.string.monitors_period_3h)
        ChartPeriod.H6 -> stringResource(R.string.monitors_period_6h)
        ChartPeriod.H24 -> stringResource(R.string.monitors_period_24h)
        ChartPeriod.W1 -> stringResource(R.string.monitors_period_1w)
    }

@Composable
private fun CertCard(cert: CertInfo) {
    val locale = currentLocale()
    val days = cert.daysRemaining
    val expired = !cert.valid || (days != null && days < 0)
    val warning = expired || (days != null && days < CERT_WARNING_DAYS)
    val color = if (warning) MaterialTheme.colorScheme.error else MaterialTheme.statusColors.up
    SectionCard(title = stringResource(R.string.monitors_certificate)) {
        Text(
            text =
                when {
                    expired -> stringResource(R.string.monitors_cert_invalid)
                    days != null -> pluralStringResource(R.plurals.monitors_cert_days, days, days)
                    else -> stringResource(R.string.monitors_cert_valid)
                },
            style = MaterialTheme.typography.titleLarge,
            color = color,
        )
        cert.issuer?.let {
            Text(stringResource(R.string.monitors_cert_issuer, it), style = MaterialTheme.typography.bodyMedium)
        }
        cert.validToMillis?.let {
            Text(
                stringResource(R.string.monitors_cert_valid_to, formatDate(it, locale)),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun EventRow(
    beat: Heartbeat,
    nowMillis: Long,
) {
    val locale = currentLocale()
    val status =
        when (beat.status) {
            MonitorStatus.UP -> DisplayStatus.UP
            MonitorStatus.DOWN -> DisplayStatus.DOWN
            MonitorStatus.PENDING -> DisplayStatus.PENDING
            MonitorStatus.MAINTENANCE -> DisplayStatus.MAINTENANCE
        }
    Row(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        StatusDot(status = status, modifier = Modifier.padding(top = 4.dp), announce = false)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(stringResource(beat.status.labelRes()), style = MaterialTheme.typography.titleSmall)
            if (beat.message.isNotBlank()) {
                Text(
                    text = beat.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(relativeTimeText(beat.timeMillis, nowMillis), style = MaterialTheme.typography.labelMedium)
            Text(
                text = formatDateTime(beat.timeMillis, locale),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EventsFooter(
    events: EventsState,
    onRetry: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        when {
            events.loading -> {
                CircularProgressIndicator(Modifier.padding(16.dp))
            }

            events.error != null -> {
                KumaErrorText(events.error)
                TextButton(onClick = onRetry) { Text(stringResource(R.string.monitors_retry)) }
            }

            events.items.isEmpty() && events.endReached -> {
                Text(
                    text = stringResource(R.string.monitors_events_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun previewState(
    overview: MonitorOverview = PreviewData.website,
    chart: ChartState = ChartState(loading = false, points = PreviewData.chart),
) = MonitorDetailUiState(
    loading = false,
    overview = overview,
    baseUrl = "https://uptime.example.org",
    chart = chart,
    events = EventsState(items = PreviewData.events, endReached = true),
)

@Preview(showBackground = true, heightDp = 1400)
@Composable
private fun DetailPreview() {
    KumaTheme(dynamicColor = false) {
        MonitorDetailScreen(state = previewState(), actions = MonitorDetailActions(), nowMillis = PreviewData.NOW)
    }
}

@Preview(showBackground = true, heightDp = 1000, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun DetailDownDarkPreview() {
    KumaTheme(dynamicColor = false) {
        MonitorDetailScreen(
            state =
                previewState(
                    overview = PreviewData.shop,
                    chart = ChartState(loading = false, error = KumaError(KumaError.Kind.TIMEOUT)),
                ),
            actions = MonitorDetailActions(),
            nowMillis = PreviewData.NOW,
        )
    }
}

@Preview(showBackground = true, heightDp = 1400, fontScale = 1.6f)
@Composable
private fun DetailLargeFontPreview() {
    KumaTheme(dynamicColor = false) {
        MonitorDetailScreen(
            state = previewState(overview = PreviewData.staging),
            actions = MonitorDetailActions(),
            nowMillis = PreviewData.NOW,
        )
    }
}
