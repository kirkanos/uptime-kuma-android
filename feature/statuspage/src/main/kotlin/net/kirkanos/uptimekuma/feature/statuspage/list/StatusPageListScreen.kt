package net.kirkanos.uptimekuma.feature.statuspage.list

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.kirkanos.uptimekuma.core.model.StatusPageAddress
import net.kirkanos.uptimekuma.core.model.StatusPageConfig
import net.kirkanos.uptimekuma.core.model.StatusPageSlug
import net.kirkanos.uptimekuma.core.ui.component.EmptyState
import net.kirkanos.uptimekuma.core.ui.component.ErrorState
import net.kirkanos.uptimekuma.core.ui.component.LoadingState
import net.kirkanos.uptimekuma.core.ui.theme.KumaTheme
import net.kirkanos.uptimekuma.feature.statuspage.PreviewData
import net.kirkanos.uptimekuma.feature.statuspage.R
import net.kirkanos.uptimekuma.feature.statuspage.slugProblemText
import net.kirkanos.uptimekuma.feature.statuspage.statusPageErrorText

data class StatusPageListActions(
    val onBack: () -> Unit = {},
    val onOpen: (String) -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onOpenCreate: () -> Unit = {},
    val onDismissCreate: () -> Unit = {},
    val onCreateTitle: (String) -> Unit = {},
    val onCreateSlug: (String) -> Unit = {},
    val onSubmitCreate: () -> Unit = {},
    val onOpenPublic: (String) -> Unit = {},
)

@Composable
internal fun StatusPageListEntry(
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onCreated: (String) -> Unit,
    onOpenPublic: (String) -> Unit,
    viewModel: StatusPageListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val created = state.create?.createdSlug
    LaunchedEffect(created) {
        if (created != null) {
            viewModel.consumeCreated()
            onCreated(created)
        }
    }
    val actions =
        remember(viewModel, onBack, onOpen, onOpenPublic) {
            StatusPageListActions(
                onBack = onBack,
                onOpen = onOpen,
                onRefresh = viewModel::refresh,
                onOpenCreate = viewModel::openCreate,
                onDismissCreate = viewModel::dismissCreate,
                onCreateTitle = viewModel::onCreateTitle,
                onCreateSlug = viewModel::onCreateSlug,
                onSubmitCreate = viewModel::submitCreate,
                onOpenPublic = onOpenPublic,
            )
        }
    StatusPageListScreen(state, actions)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StatusPageListScreen(
    state: StatusPageListUiState,
    actions: StatusPageListActions,
) {
    var publicDialog by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.statuspage_list_title)) },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.statuspage_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { publicDialog = true }) {
                        Icon(
                            Icons.Outlined.Public,
                            contentDescription = stringResource(R.string.statuspage_view_public),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = actions.onOpenCreate,
                icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.statuspage_create)) },
            )
        },
    ) { padding ->
        val pages = state.pages
        val loadError = state.loadError
        when {
            pages == null && loadError != null -> {
                ErrorState(loadError, Modifier.padding(padding), onRetry = actions.onRefresh)
            }

            pages == null -> {
                LoadingState(Modifier.padding(padding))
            }

            else -> {
                PullToRefreshBox(
                    isRefreshing = state.refreshing,
                    onRefresh = actions.onRefresh,
                    modifier = Modifier.fillMaxSize().padding(padding),
                ) {
                    StatusPageList(pages, actions, onPublic = { publicDialog = true })
                }
            }
        }
    }
    state.create?.let { CreateDialog(it, actions) }
    if (publicDialog) {
        PublicUrlDialog(
            prefill = state.publicUrlPrefill,
            onOpen = { url ->
                publicDialog = false
                actions.onOpenPublic(url)
            },
            onDismiss = { publicDialog = false },
        )
    }
}

@Composable
private fun StatusPageList(
    pages: List<StatusPageConfig>,
    actions: StatusPageListActions,
    onPublic: () -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
        if (pages.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    title = stringResource(R.string.statuspage_empty_title),
                    message = stringResource(R.string.statuspage_empty_message),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        items(pages, key = { it.slug }) { page ->
            StatusPageRow(page, onClick = { actions.onOpen(page.slug) })
            HorizontalDivider()
        }
        item(key = "public") {
            ListItem(
                headlineContent = { Text(stringResource(R.string.statuspage_view_public)) },
                supportingContent = { Text(stringResource(R.string.statuspage_public_message)) },
                leadingContent = { Icon(Icons.Outlined.Public, contentDescription = null) },
                modifier = Modifier.clickable(onClick = onPublic),
            )
        }
    }
}

@Composable
private fun StatusPageRow(
    page: StatusPageConfig,
    onClick: () -> Unit,
) {
    val publishedLabel =
        stringResource(if (page.published) R.string.statuspage_published else R.string.statuspage_unpublished)
    ListItem(
        headlineContent = { Text(page.title) },
        supportingContent = { Text(stringResource(R.string.statuspage_slug_path, page.slug)) },
        trailingContent = {
            Icon(
                if (page.published) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                contentDescription = null,
                tint =
                    if (page.published) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
        },
        overlineContent = { Text(publishedLabel) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun CreateDialog(
    state: CreateState,
    actions: StatusPageListActions,
) {
    val slugProblem = state.slugProblem.takeIf { state.slug.isNotEmpty() || state.slugEdited }
    AlertDialog(
        onDismissRequest = actions.onDismissCreate,
        title = { Text(stringResource(R.string.statuspage_create)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.title,
                    onValueChange = actions.onCreateTitle,
                    label = { Text(stringResource(R.string.statuspage_field_name)) },
                    singleLine = true,
                    keyboardOptions =
                        KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            imeAction = ImeAction.Next,
                        ),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.slug,
                    onValueChange = actions.onCreateSlug,
                    label = { Text(stringResource(R.string.statuspage_field_slug)) },
                    prefix = { Text("/status/") },
                    singleLine = true,
                    isError = slugProblem != null,
                    supportingText = {
                        Text(slugProblem?.let { slugProblemText(it) } ?: stringResource(R.string.statuspage_slug_help))
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (StatusPageSlug.normalize(state.slug) == StatusPageSlug.SPECIAL_DEFAULT) {
                    Text(
                        stringResource(R.string.statuspage_slug_default_hint),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                state.error?.let {
                    Text(
                        statusPageErrorText(it),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = actions.onSubmitCreate, enabled = state.canSubmit) {
                Text(stringResource(R.string.statuspage_create_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = actions.onDismissCreate, enabled = !state.inProgress) {
                Text(stringResource(R.string.statuspage_cancel))
            }
        },
    )
}

@Composable
private fun PublicUrlDialog(
    prefill: String,
    onOpen: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var url by rememberSaveable { mutableStateOf(prefill) }
    val valid = StatusPageAddress.parse(url) != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.statuspage_view_public)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.statuspage_public_message))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it.trim() },
                    label = { Text(stringResource(R.string.statuspage_public_url)) },
                    placeholder = { Text(stringResource(R.string.statuspage_public_url_hint)) },
                    singleLine = true,
                    isError = url.isNotEmpty() && !valid,
                    supportingText = {
                        if (url.isNotEmpty() && !valid) Text(stringResource(R.string.statuspage_public_url_invalid))
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onOpen(url) },
                enabled = valid,
            ) { Text(stringResource(R.string.statuspage_public_open)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.statuspage_cancel)) } },
    )
}

@Preview(showBackground = true)
@Composable
private fun StatusPageListPreview() {
    KumaTheme(dynamicColor = false) {
        StatusPageListScreen(StatusPageListUiState(pages = PreviewData.pages), StatusPageListActions())
    }
}

@Preview(showBackground = true)
@Composable
private fun StatusPageListEmptyPreview() {
    KumaTheme(dynamicColor = false) {
        StatusPageListScreen(StatusPageListUiState(pages = emptyList()), StatusPageListActions())
    }
}

@Preview(showBackground = true)
@Composable
private fun StatusPageCreatePreview() {
    KumaTheme(dynamicColor = false) {
        StatusPageListScreen(
            StatusPageListUiState(
                pages = PreviewData.pages,
                create = CreateState(title = "My Status", slug = "my--status", slugEdited = true),
            ),
            StatusPageListActions(),
        )
    }
}
