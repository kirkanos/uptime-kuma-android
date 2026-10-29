package net.kirkanos.uptimekuma.feature.notifications.list

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.kirkanos.uptimekuma.core.domain.notification.NotificationForm
import net.kirkanos.uptimekuma.core.domain.notification.NotificationProviderSpec
import net.kirkanos.uptimekuma.core.model.KumaError
import net.kirkanos.uptimekuma.core.ui.component.EmptyState
import net.kirkanos.uptimekuma.core.ui.component.KumaErrorText
import net.kirkanos.uptimekuma.core.ui.component.LoadingState
import net.kirkanos.uptimekuma.core.ui.theme.KumaTheme
import net.kirkanos.uptimekuma.feature.notifications.PreviewData
import net.kirkanos.uptimekuma.feature.notifications.R
import net.kirkanos.uptimekuma.feature.notifications.icon
import net.kirkanos.uptimekuma.feature.notifications.labelRes

@Immutable
internal class NotificationListActions(
    val onBack: () -> Unit = {},
    val onAdd: () -> Unit = {},
    val onOpen: (Long) -> Unit = {},
    val onRefresh: () -> Unit = {},
)

@Composable
internal fun NotificationListEntry(
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onOpen: (Long) -> Unit,
    viewModel: NotificationListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val actions =
        remember(viewModel, onBack, onAdd, onOpen) {
            NotificationListActions(onBack = onBack, onAdd = onAdd, onOpen = onOpen, onRefresh = viewModel::refresh)
        }
    NotificationListScreen(state, actions)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NotificationListScreen(
    state: NotificationListUiState,
    actions: NotificationListActions,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.notif_title)) },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.notif_back))
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = actions.onAdd,
                icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.notif_add)) },
            )
        },
    ) { padding ->
        val contentModifier = Modifier.padding(padding).fillMaxSize()
        val rows = state.rows
        if (rows == null) {
            LoadingState(contentModifier)
            return@Scaffold
        }
        PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = actions.onRefresh, modifier = contentModifier) {
            if (rows.isEmpty()) {
                EmptyState(
                    title = stringResource(R.string.notif_list_empty_title),
                    message =
                        state.connectError?.let { stringResource(R.string.notif_list_offline) }
                            ?: stringResource(R.string.notif_list_empty_message),
                    icon = Icons.Outlined.NotificationsNone,
                    actionLabel = stringResource(R.string.notif_add),
                    onAction = actions.onAdd,
                )
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 96.dp), modifier = Modifier.fillMaxSize()) {
                    state.connectError?.let { error ->
                        item(key = "error") { ConnectErrorBanner(error) }
                    }
                    items(rows, key = { it.notification.id ?: 0L }) { row ->
                        NotificationRowItem(row, onClick = { row.notification.id?.let(actions.onOpen) })
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectErrorBanner(error: KumaError) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.padding(16.dp)) {
        KumaErrorText(
            error = error,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun NotificationRowItem(
    row: NotificationRow,
    onClick: () -> Unit,
) {
    val provider = row.provider
    val providerLabel = provider?.label ?: stringResource(R.string.notif_unknown_provider, row.notification.type)
    val categoryLabel = provider?.categories?.firstOrNull()?.let { stringResource(it.labelRes()) }
    ListItem(
        headlineContent = { Text(row.notification.name) },
        supportingContent = {
            Column {
                Text(providerLabel)
                val badges =
                    buildList {
                        if (row.isThisDevice) add(stringResource(R.string.notif_badge_this_device) to true)
                        if (row.notification.isDefault) add(stringResource(R.string.notif_badge_default) to false)
                        if (!row.notification.active) add(stringResource(R.string.notif_badge_inactive) to false)
                    }
                if (badges.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        badges.forEach { (text, highlight) -> Badge(text, highlight) }
                    }
                }
            }
        },
        leadingContent = {
            Icon(
                if (row.isThisDevice) Icons.Outlined.PhoneAndroid else provider.icon,
                contentDescription = categoryLabel,
            )
        },
        modifier = Modifier.clickable(onClick = onClick).semantics(mergeDescendants = true) {},
    )
}

@Composable
private fun Badge(
    text: String,
    highlight: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    val container: Color = if (highlight) colors.primaryContainer else colors.secondaryContainer
    val content: Color = if (highlight) colors.onPrimaryContainer else colors.onSecondaryContainer
    Surface(
        color = container,
        shape = MaterialTheme.shapes.small,
        modifier =
            Modifier.semantics {
                contentDescription =
                    text
            },
    ) {
        Text(
            text,
            color = content,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

private fun previewState(): NotificationListUiState {
    val spec = NotificationProviderSpec.bundled
    return NotificationListUiState(
        rows =
            PreviewData.all.map {
                NotificationRow(it, spec.provider(it.type), NotificationForm.isThisDevicePush(it))
            },
    )
}

@Preview(showBackground = true)
@Composable
private fun NotificationListPreview() {
    KumaTheme(dynamicColor = false) { NotificationListScreen(previewState(), NotificationListActions()) }
}

@Preview(showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun NotificationListDarkPreview() {
    KumaTheme(darkTheme = true, dynamicColor = false) {
        NotificationListScreen(
            previewState().copy(connectError = KumaError(KumaError.Kind.NETWORK)),
            NotificationListActions(),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun NotificationListEmptyPreview() {
    KumaTheme(dynamicColor = false) {
        NotificationListScreen(NotificationListUiState(rows = emptyList()), NotificationListActions())
    }
}
