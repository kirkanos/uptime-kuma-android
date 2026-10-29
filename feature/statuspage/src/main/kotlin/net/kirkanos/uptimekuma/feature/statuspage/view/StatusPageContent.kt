package net.kirkanos.uptimekuma.feature.statuspage.view

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.kirkanos.uptimekuma.core.model.Incident
import net.kirkanos.uptimekuma.core.model.OverallStatus
import net.kirkanos.uptimekuma.core.model.StatusPageGroup
import net.kirkanos.uptimekuma.core.model.StatusPageHeartbeats
import net.kirkanos.uptimekuma.core.model.StatusPageMaintenance
import net.kirkanos.uptimekuma.core.model.StatusPageMonitor
import net.kirkanos.uptimekuma.core.ui.component.HeartbeatBar
import net.kirkanos.uptimekuma.core.ui.component.TagChip
import net.kirkanos.uptimekuma.core.ui.component.UptimeBadge
import net.kirkanos.uptimekuma.core.ui.format.currentLocale
import net.kirkanos.uptimekuma.core.ui.format.formatDateTime
import net.kirkanos.uptimekuma.core.ui.theme.KumaTheme
import net.kirkanos.uptimekuma.feature.statuspage.PreviewData
import net.kirkanos.uptimekuma.feature.statuspage.R
import net.kirkanos.uptimekuma.feature.statuspage.incidentColors
import net.kirkanos.uptimekuma.feature.statuspage.incidentStyleLabel
import net.kirkanos.uptimekuma.feature.statuspage.overallStatusColor
import net.kirkanos.uptimekuma.feature.statuspage.overallStatusLabel
import net.kirkanos.uptimekuma.feature.statuspage.statusPageErrorText
import net.kirkanos.uptimekuma.feature.statuspage.supportsLink

private const val MAX_BARS = 40
private const val BANNER_TINT = 0.16f

/**
 * The rendered public view of a status page (like `/status/<slug>`): title, overall status, pinned incident,
 * active maintenance, groups with heartbeat bars and 24 h uptime, footer and incident history.
 * [header] adds items at the top (e.g. "not published"); [incidentActions] adds admin buttons to the incident card.
 */
@Composable
fun StatusPageContent(
    state: StatusPageViewState,
    onLoadMoreHistory: () -> Unit,
    onRetryHistory: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    header: LazyListScope.() -> Unit = {},
    incidentActions: (@Composable (Incident) -> Unit)? = null,
) {
    val page = state.page ?: return
    val heartbeats = state.heartbeats
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        header()
        item(key = "title") { TitleBlock(page.config.title, page.config.description) }
        item(key = "overall") { OverallBanner(heartbeats.overallStatus) }
        page.incident?.let { incident ->
            item(key = "incident") { IncidentCard(incident, actions = incidentActions) }
        }
        items(page.maintenances, key = { "maintenance-${it.id}" }) { MaintenanceCard(it) }
        if (page.groups.isEmpty()) {
            item(key = "no-groups") {
                Text(
                    stringResource(R.string.statuspage_groups_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        page.groups.forEachIndexed { index, group ->
            item(key = "group-$index-${group.id}") {
                GroupCard(group, heartbeats, onlyLastBeat = page.config.showOnlyLastHeartbeat)
            }
        }
        item(key = "footer") { Footer(page.config.footerText, page.config.showPoweredBy) }
        historySection(state.history, onLoadMoreHistory, onRetryHistory)
    }
}

@Composable
private fun TitleBlock(
    title: String,
    description: String?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        description?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun OverallBanner(
    status: OverallStatus,
    modifier: Modifier = Modifier,
) {
    val color = overallStatusColor(status)
    val icon =
        when (status) {
            OverallStatus.ALL_UP -> Icons.Outlined.CheckCircle
            OverallStatus.PARTIAL_DOWN -> Icons.Outlined.WarningAmber
            OverallStatus.ALL_DOWN -> Icons.Outlined.ErrorOutline
            OverallStatus.MAINTENANCE -> Icons.Outlined.Build
            OverallStatus.UNKNOWN -> Icons.AutoMirrored.Outlined.HelpOutline
        }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = color.copy(alpha = BANNER_TINT),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(28.dp))
            Text(
                overallStatusLabel(status),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() },
            )
        }
    }
}

@Composable
fun IncidentCard(
    incident: Incident,
    modifier: Modifier = Modifier,
    actions: (@Composable (Incident) -> Unit)? = null,
) {
    val (background, content) = incidentColors(incident.style)
    val locale = currentLocale()
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = background,
        contentColor = content,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(R.string.statuspage_incident_label) + " · " + incidentStyleLabel(incident.style),
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                incident.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() },
            )
            incident.content?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            incident.createdMillis?.let {
                Text(
                    stringResource(R.string.statuspage_incident_created, formatDateTime(it, locale)),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            incident.lastUpdatedMillis?.let {
                Text(
                    stringResource(R.string.statuspage_incident_updated, formatDateTime(it, locale)),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            actions?.invoke(incident)
        }
    }
}

@Composable
private fun MaintenanceCard(maintenance: StatusPageMaintenance) {
    val color = overallStatusColor(OverallStatus.MAINTENANCE)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = color.copy(alpha = BANNER_TINT),
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.Build, contentDescription = null, tint = color)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.statuspage_maintenance_label),
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(maintenance.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                maintenance.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}

@Composable
private fun GroupCard(
    group: StatusPageGroup,
    heartbeats: StatusPageHeartbeats,
    onlyLastBeat: Boolean,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 12.dp)) {
            Text(
                group.name,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp).semantics { heading() },
            )
            group.monitors.forEachIndexed { index, monitor ->
                if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                MonitorRow(monitor, heartbeats, onlyLastBeat)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MonitorRow(
    monitor: StatusPageMonitor,
    heartbeats: StatusPageHeartbeats,
    onlyLastBeat: Boolean,
) {
    val uriHandler = LocalUriHandler.current
    val beats = heartbeats.beats[monitor.id].orEmpty()
    val shownBeats = remember(beats, onlyLastBeat) { if (onlyLastBeat) beats.takeLast(1) else beats }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            UptimeBadge(heartbeats.uptime24h[monitor.id])
            Text(monitor.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            monitor.clickableUrl?.let { url ->
                IconButton(onClick = { uriHandler.openUri(url) }) {
                    Icon(
                        Icons.AutoMirrored.Outlined.OpenInNew,
                        contentDescription = stringResource(R.string.statuspage_open_link, monitor.name),
                    )
                }
            }
        }
        HeartbeatBar(
            beats = shownBeats,
            maxBars = if (onlyLastBeat) 1 else MAX_BARS,
            modifier = Modifier.fillMaxWidth(),
        )
        if (monitor.tags.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                monitor.tags.forEach { TagChip(it) }
            }
        }
        CertLine(monitor)
    }
}

@Composable
private fun CertLine(monitor: StatusPageMonitor) {
    val days = monitor.certExpiryDaysRemaining
    when {
        monitor.validCert == true && days != null -> {
            Text(
                pluralStringResource(R.plurals.statuspage_cert_days, days, days),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Kuma reports validCert:false for every non-TLS monitor; only flag HTTP-like types.
        monitor.validCert == false && supportsLink(monitor.type) -> {
            Text(
                stringResource(R.string.statuspage_cert_invalid),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun Footer(
    footerText: String?,
    showPoweredBy: Boolean,
) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        footerText?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (showPoweredBy) {
            Text(
                stringResource(R.string.statuspage_powered_by),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun LazyListScope.historySection(
    history: HistoryState,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
) {
    item(key = "history-title") {
        Text(
            stringResource(R.string.statuspage_history_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 8.dp).semantics { heading() },
        )
    }
    if (history.loaded && history.items.isEmpty() && history.error == null) {
        item(key = "history-empty") {
            Text(
                stringResource(R.string.statuspage_history_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    items(history.items, key = { "history-${it.id}" }) { HistoryItem(it) }
    item(key = "history-footer") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            when {
                history.loading -> {
                    CircularProgressIndicator(Modifier.size(24.dp))
                }

                history.error != null -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(statusPageErrorText(history.error), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = onRetry) { Text(stringResource(R.string.statuspage_retry)) }
                    }
                }

                history.hasMore -> {
                    OutlinedButton(onClick = onLoadMore) { Text(stringResource(R.string.statuspage_history_more)) }
                }
            }
        }
    }
}

@Composable
private fun HistoryItem(incident: Incident) {
    val locale = currentLocale()
    val (background, _) = incidentColors(incident.style)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        Surface(
            color = background,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.size(width = 6.dp, height = 40.dp),
        ) {
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    incident.title,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (incident.pin && incident.active) {
                    Icon(
                        Icons.Outlined.PushPin,
                        contentDescription = stringResource(R.string.statuspage_history_pinned),
                        modifier = Modifier.size(16.dp),
                    )
                } else if (!incident.active) {
                    Text(
                        stringResource(R.string.statuspage_history_resolved),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            incident.content?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 3)
            }
            incident.createdMillis?.let {
                Text(
                    formatDateTime(it, locale),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Preview(showBackground = true, heightDp = 1400)
@Preview(showBackground = true, heightDp = 1400, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun StatusPageContentPreview() {
    KumaTheme(dynamicColor = false) {
        Surface {
            StatusPageContent(state = PreviewData.viewState(), onLoadMoreHistory = {}, onRetryHistory = {})
        }
    }
}
