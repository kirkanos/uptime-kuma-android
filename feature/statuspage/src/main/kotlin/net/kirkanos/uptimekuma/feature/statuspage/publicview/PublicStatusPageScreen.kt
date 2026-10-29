package net.kirkanos.uptimekuma.feature.statuspage.publicview

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.kirkanos.uptimekuma.core.model.StatusPageAddress
import net.kirkanos.uptimekuma.core.ui.component.EmptyState
import net.kirkanos.uptimekuma.core.ui.component.LoadingState
import net.kirkanos.uptimekuma.core.ui.theme.KumaTheme
import net.kirkanos.uptimekuma.feature.statuspage.PreviewData
import net.kirkanos.uptimekuma.feature.statuspage.R
import net.kirkanos.uptimekuma.feature.statuspage.detail.AutoRefresh
import net.kirkanos.uptimekuma.feature.statuspage.detail.RefreshErrorSnackbar
import net.kirkanos.uptimekuma.feature.statuspage.statusPageErrorText
import net.kirkanos.uptimekuma.feature.statuspage.view.StatusPageContent

data class PublicStatusPageActions(
    val onBack: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onAutoRefresh: () -> Unit = {},
    val onLoadMoreHistory: () -> Unit = {},
    val onRetryHistory: () -> Unit = {},
    val onDismissRefreshError: () -> Unit = {},
)

@Composable
internal fun PublicStatusPageEntry(
    onBack: () -> Unit,
    viewModel: PublicStatusPageViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val actions =
        remember(viewModel, onBack) {
            PublicStatusPageActions(
                onBack = onBack,
                onRefresh = viewModel::refresh,
                onAutoRefresh = viewModel::refreshHeartbeats,
                onLoadMoreHistory = viewModel::loadMoreHistory,
                onRetryHistory = viewModel::retryHistory,
                onDismissRefreshError = viewModel::dismissRefreshError,
            )
        }
    PublicStatusPageScreen(state, actions)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PublicStatusPageScreen(
    state: PublicStatusPageUiState,
    actions: PublicStatusPageActions,
) {
    val uriHandler = LocalUriHandler.current
    val snackbar = remember { SnackbarHostState() }
    val view = state.view
    val page = view.page
    AutoRefresh(page?.config?.autoRefreshInterval, actions.onAutoRefresh)
    RefreshErrorSnackbar(view, snackbar, actions.onDismissRefreshError)
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        page?.config?.title ?: stringResource(R.string.statuspage_public_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.statuspage_back),
                        )
                    }
                },
                actions = {
                    state.address?.let { address ->
                        IconButton(onClick = actions.onRefresh) {
                            Icon(
                                Icons.Outlined.Refresh,
                                contentDescription = stringResource(R.string.statuspage_refresh),
                            )
                        }
                        IconButton(onClick = { uriHandler.openUri(address.webUrl) }) {
                            Icon(
                                Icons.AutoMirrored.Outlined.OpenInNew,
                                contentDescription = stringResource(R.string.statuspage_open_browser),
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val error = view.error
        when {
            state.invalidUrl -> {
                EmptyState(
                    title = stringResource(R.string.statuspage_public_invalid_title),
                    message = stringResource(R.string.statuspage_public_url_invalid),
                    icon = Icons.Outlined.LinkOff,
                    modifier = Modifier.padding(padding),
                )
            }

            view.loading -> {
                LoadingState(Modifier.padding(padding))
            }

            page == null && error != null -> {
                EmptyState(
                    title = state.address?.webUrl.orEmpty(),
                    message = statusPageErrorText(error),
                    icon = Icons.Outlined.LinkOff,
                    actionLabel = stringResource(R.string.statuspage_retry),
                    onAction = actions.onRefresh,
                    modifier = Modifier.padding(padding),
                )
            }

            page != null -> {
                PullToRefreshBox(
                    isRefreshing = view.refreshing,
                    onRefresh = actions.onRefresh,
                    modifier = Modifier.fillMaxSize().padding(padding),
                ) {
                    StatusPageContent(
                        state = view,
                        onLoadMoreHistory = actions.onLoadMoreHistory,
                        onRetryHistory = actions.onRetryHistory,
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true, heightDp = 1200)
@Composable
private fun PublicStatusPagePreview() {
    KumaTheme(dynamicColor = false) {
        PublicStatusPageScreen(
            PublicStatusPageUiState(StatusPageAddress("https://status.example.org", "status"), PreviewData.viewState()),
            PublicStatusPageActions(),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PublicStatusPageInvalidPreview() {
    KumaTheme(dynamicColor = false) {
        PublicStatusPageScreen(PublicStatusPageUiState(address = null), PublicStatusPageActions())
    }
}
