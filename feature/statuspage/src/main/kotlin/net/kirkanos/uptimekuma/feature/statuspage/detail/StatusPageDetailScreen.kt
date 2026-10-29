package net.kirkanos.uptimekuma.feature.statuspage.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import net.kirkanos.uptimekuma.core.model.Incident
import net.kirkanos.uptimekuma.core.model.IncidentDraft
import net.kirkanos.uptimekuma.core.model.IncidentStyle
import net.kirkanos.uptimekuma.core.ui.component.ErrorState
import net.kirkanos.uptimekuma.core.ui.component.LoadingState
import net.kirkanos.uptimekuma.core.ui.theme.KumaTheme
import net.kirkanos.uptimekuma.feature.statuspage.PreviewData
import net.kirkanos.uptimekuma.feature.statuspage.R
import net.kirkanos.uptimekuma.feature.statuspage.incidentStyleLabel
import net.kirkanos.uptimekuma.feature.statuspage.statusPageErrorText
import net.kirkanos.uptimekuma.feature.statuspage.view.StatusPageContent
import net.kirkanos.uptimekuma.feature.statuspage.view.StatusPageViewState

/** Callbacks of the detail screen. */
data class StatusPageDetailActions(
    val onBack: () -> Unit = {},
    val onEdit: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onAutoRefresh: () -> Unit = {},
    val onLoadMoreHistory: () -> Unit = {},
    val onRetryHistory: () -> Unit = {},
    val onPostIncident: (IncidentDraft) -> Unit = {},
    val onUnpin: () -> Unit = {},
    val onDelete: () -> Unit = {},
    val onDismissActionError: () -> Unit = {},
    val onDismissRefreshError: () -> Unit = {},
)

@Composable
internal fun StatusPageDetailEntry(
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    viewModel: StatusPageDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.deleted) {
        if (state.deleted) onBack()
    }
    val actions =
        remember(viewModel, onBack, onEdit) {
            StatusPageDetailActions(
                onBack = onBack,
                onEdit = { onEdit(viewModel.slug) },
                onRefresh = viewModel::refresh,
                onAutoRefresh = viewModel::refreshHeartbeats,
                onLoadMoreHistory = viewModel::loadMoreHistory,
                onRetryHistory = viewModel::retryHistory,
                onPostIncident = viewModel::postIncident,
                onUnpin = viewModel::unpinIncident,
                onDelete = viewModel::delete,
                onDismissActionError = viewModel::dismissActionError,
                onDismissRefreshError = viewModel::dismissRefreshError,
            )
        }
    StatusPageDetailScreen(state, actions)
}

/** Refreshes heartbeats every `autoRefreshInterval` seconds (at least 60 s: Kuma caches them for 1 min). */
@Composable
internal fun AutoRefresh(
    intervalSeconds: Int?,
    onRefresh: () -> Unit,
) {
    val seconds = intervalSeconds?.coerceAtLeast(MIN_AUTO_REFRESH_SECONDS) ?: return
    LaunchedEffect(seconds) {
        while (true) {
            delay(seconds * MILLIS)
            onRefresh()
        }
    }
}

private const val MIN_AUTO_REFRESH_SECONDS = 60
private const val MILLIS = 1_000L

/** Shows [StatusPageViewState.refreshError] once in [snackbar]. */
@Composable
internal fun RefreshErrorSnackbar(
    view: StatusPageViewState,
    snackbar: SnackbarHostState,
    onDismiss: () -> Unit,
) {
    val error = view.refreshError ?: return
    val message = stringResource(R.string.statuspage_refresh_failed, statusPageErrorText(error))
    LaunchedEffect(error) {
        snackbar.showSnackbar(message)
        onDismiss()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StatusPageDetailScreen(
    state: StatusPageDetailUiState,
    actions: StatusPageDetailActions,
) {
    val snackbar = remember { SnackbarHostState() }
    var editorFor by rememberSaveable { mutableStateOf<Long?>(null) }
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val page = state.view.page

    AutoRefresh(page?.config?.autoRefreshInterval, actions.onAutoRefresh)
    RefreshErrorSnackbar(state.view, snackbar, actions.onDismissRefreshError)
    LaunchedEffect(state.incidentPosted) {
        if (state.incidentPosted > 0) editorOpen = false
    }
    state.actionError?.let { error ->
        val message = statusPageErrorText(error)
        LaunchedEffect(error) {
            snackbar.showSnackbar(message)
            actions.onDismissActionError()
        }
    }

    Scaffold(
        topBar = {
            DetailTopBar(
                title = page?.config?.title ?: state.config?.title ?: state.slug,
                webUrl = state.webUrl,
                hasIncident = page?.incident != null,
                actions = actions,
                onNewIncident = {
                    editorFor = null
                    editorOpen = true
                },
                onDelete = { confirmDelete = true },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val view = state.view
        when {
            view.loading -> {
                LoadingState(Modifier.padding(padding))
            }

            page == null -> {
                ErrorState(
                    error = view.error ?: return@Scaffold,
                    onRetry = actions.onRefresh,
                    modifier = Modifier.padding(padding),
                )
            }

            else -> {
                PullToRefreshBox(
                    isRefreshing = view.refreshing,
                    onRefresh = actions.onRefresh,
                    modifier = Modifier.fillMaxSize().padding(padding),
                ) {
                    StatusPageContent(
                        state = view,
                        onLoadMoreHistory = actions.onLoadMoreHistory,
                        onRetryHistory = actions.onRetryHistory,
                        contentPadding = PaddingValues(16.dp),
                        header = {
                            if (state.config?.published == false) {
                                item(key = "unpublished") { UnpublishedNote() }
                            }
                        },
                        incidentActions = { incident ->
                            IncidentButtons(
                                busy = state.actionInProgress != null,
                                onEdit = {
                                    editorFor = incident.id
                                    editorOpen = true
                                },
                                onUnpin = actions.onUnpin,
                            )
                        },
                    )
                }
            }
        }
    }

    if (editorOpen) {
        val existing = page?.incident?.takeIf { it.id == editorFor }
        IncidentEditorDialog(
            initial = existing,
            busy = state.actionInProgress == StatusPageAction.POST_INCIDENT,
            onPost = actions.onPostIncident,
            onDismiss = { editorOpen = false },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.statuspage_delete_title)) },
            text = { Text(stringResource(R.string.statuspage_delete_message, page?.config?.title ?: state.slug)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        actions.onDelete()
                    },
                ) { Text(stringResource(R.string.statuspage_delete_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.statuspage_cancel)) }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailTopBar(
    title: String,
    webUrl: String?,
    hasIncident: Boolean,
    actions: StatusPageDetailActions,
    onNewIncident: () -> Unit,
    onDelete: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    var menu by remember { mutableStateOf(false) }
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            IconButton(onClick = actions.onBack) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(R.string.statuspage_back),
                )
            }
        },
        actions = {
            IconButton(onClick = actions.onEdit) {
                Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.statuspage_edit))
            }
            if (webUrl != null) {
                IconButton(onClick = { uriHandler.openUri(webUrl) }) {
                    Icon(
                        Icons.AutoMirrored.Outlined.OpenInNew,
                        contentDescription = stringResource(R.string.statuspage_open_browser),
                    )
                }
            }
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.statuspage_more))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.statuspage_post_incident)) },
                    leadingIcon = { Icon(Icons.Outlined.Campaign, contentDescription = null) },
                    onClick = {
                        menu = false
                        onNewIncident()
                    },
                )
                if (hasIncident) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.statuspage_unpin)) },
                        leadingIcon = { Icon(Icons.Outlined.VisibilityOff, contentDescription = null) },
                        onClick = {
                            menu = false
                            actions.onUnpin()
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.statuspage_refresh)) },
                    leadingIcon = { Icon(Icons.Outlined.Refresh, contentDescription = null) },
                    onClick = {
                        menu = false
                        actions.onRefresh()
                    },
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(R.string.statuspage_delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    },
                    onClick = {
                        menu = false
                        onDelete()
                    },
                )
            }
        },
    )
}

@Composable
private fun UnpublishedNote() {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.VisibilityOff, contentDescription = null)
            Text(stringResource(R.string.statuspage_not_published), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun IncidentButtons(
    busy: Boolean,
    onEdit: () -> Unit,
    onUnpin: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
        OutlinedButton(onClick = onEdit, enabled = !busy) { Text(stringResource(R.string.statuspage_edit_incident)) }
        OutlinedButton(onClick = onUnpin, enabled = !busy) { Text(stringResource(R.string.statuspage_unpin)) }
    }
}

/** Title, content (Markdown as plain text) and style chips; [initial] set = edit that incident. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun IncidentEditorDialog(
    initial: Incident?,
    busy: Boolean,
    onPost: (IncidentDraft) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by rememberSaveable { mutableStateOf(initial?.title.orEmpty()) }
    var content by rememberSaveable { mutableStateOf(initial?.content.orEmpty()) }
    var style by rememberSaveable { mutableStateOf(initial?.style ?: IncidentStyle.DEFAULT) }
    var showMissing by rememberSaveable { mutableStateOf(false) }
    val missing = title.isBlank() || content.isBlank()
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = {
            Text(
                stringResource(
                    if (initial ==
                        null
                    ) {
                        R.string.statuspage_post_incident
                    } else {
                        R.string.statuspage_edit_incident
                    },
                ),
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.statuspage_incident_title)) },
                    singleLine = true,
                    isError = showMissing && title.isBlank(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text(stringResource(R.string.statuspage_incident_content)) },
                    supportingText = { Text(stringResource(R.string.statuspage_markdown_hint)) },
                    isError = showMissing && content.isBlank(),
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.statuspage_incident_style), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IncidentStyle.entries.forEach { option ->
                        FilterChip(
                            selected = option == style,
                            onClick = { style = option },
                            label = { Text(incidentStyleLabel(option)) },
                        )
                    }
                }
                if (showMissing && missing) {
                    Text(
                        stringResource(R.string.statuspage_incident_missing),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    if (missing) {
                        showMissing = true
                    } else {
                        onPost(IncidentDraft(title = title, content = content, style = style, id = initial?.id))
                    }
                },
            ) { Text(stringResource(R.string.statuspage_incident_post)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.statuspage_cancel)) }
        },
    )
}

@Preview(showBackground = true, heightDp = 1200)
@Composable
private fun StatusPageDetailPreview() {
    KumaTheme(dynamicColor = false) {
        StatusPageDetailScreen(
            state =
                StatusPageDetailUiState(
                    slug = "status",
                    view = PreviewData.viewState(),
                    config = PreviewData.config.copy(published = false),
                    baseUrl = "https://uptime.example.org",
                ),
            actions = StatusPageDetailActions(),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun IncidentEditorPreview() {
    KumaTheme(dynamicColor = false) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IncidentEditorDialog(initial = PreviewData.incident, busy = false, onPost = {}, onDismiss = {})
            }
        }
    }
}
