package net.kirkanos.uptimekuma.feature.monitors.tags

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
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
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.kirkanos.uptimekuma.core.model.Tag
import net.kirkanos.uptimekuma.core.ui.component.EmptyState
import net.kirkanos.uptimekuma.core.ui.component.parseHexColor
import net.kirkanos.uptimekuma.core.ui.theme.KumaTheme
import net.kirkanos.uptimekuma.feature.monitors.R
import net.kirkanos.uptimekuma.feature.monitors.editor.ConfirmDialog
import net.kirkanos.uptimekuma.feature.monitors.editor.editorErrorText

@Immutable
internal class TagManagerActions(
    val onBack: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onCreate: (String, String) -> Unit = { _, _ -> },
    val onUpdate: (Tag) -> Unit = {},
    val onDelete: (Long) -> Unit = {},
    val onDismissError: () -> Unit = {},
)

@Composable
internal fun TagManagerEntry(
    onBack: () -> Unit,
    viewModel: TagManagerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val actions =
        remember(viewModel, onBack) {
            TagManagerActions(
                onBack = onBack,
                onRefresh = viewModel::refresh,
                onCreate = viewModel::create,
                onUpdate = viewModel::update,
                onDelete = viewModel::delete,
                onDismissError = viewModel::dismissError,
            )
        }
    TagManagerScreen(state, actions)
}

/** `null` id = create a new tag. */
private data class TagEdit(
    val id: Long?,
    val name: String,
    val color: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TagManagerScreen(
    state: TagManagerUiState,
    actions: TagManagerActions,
    modifier: Modifier = Modifier,
) {
    val snackbar = remember { SnackbarHostState() }
    val errorText = state.error?.let { editorErrorText(it) }
    LaunchedEffect(errorText) {
        if (errorText != null) {
            snackbar.showSnackbar(errorText)
            actions.onDismissError()
        }
    }
    var editing by remember { mutableStateOf<TagEdit?>(null) }
    var deleting by remember { mutableStateOf<Tag?>(null) }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tags_title)) },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.monitors_back),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { editing = TagEdit(null, "", TAG_COLORS[4].first) },
                icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.tags_new)) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = actions.onRefresh,
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            if (state.tags.isEmpty() && !state.refreshing) {
                EmptyState(
                    title = stringResource(R.string.tags_empty_title),
                    message = stringResource(R.string.tags_empty_message),
                    icon = Icons.AutoMirrored.Outlined.Label,
                )
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                    items(state.tags, key = { it.id }) { tag ->
                        ListItem(
                            headlineContent = { Text(tag.name) },
                            supportingContent = { Text(tag.color) },
                            leadingContent = { Swatch(tag.color) },
                            trailingContent = {
                                IconButton(onClick = { deleting = tag }, enabled = !state.busy) {
                                    Icon(
                                        Icons.Outlined.Delete,
                                        contentDescription = stringResource(R.string.tags_delete_named, tag.name),
                                    )
                                }
                            },
                            modifier =
                                Modifier.clickable(onClickLabel = stringResource(R.string.tags_edit)) {
                                    editing = TagEdit(tag.id, tag.name, tag.color)
                                },
                        )
                    }
                }
            }
        }
    }
    editing?.let { edit ->
        TagEditDialog(
            initial = edit,
            onSave = { name, color ->
                editing = null
                if (edit.id == null) actions.onCreate(name, color) else actions.onUpdate(Tag(edit.id, name, color))
            },
            onDismiss = { editing = null },
        )
    }
    deleting?.let { tag ->
        ConfirmDialog(
            title = stringResource(R.string.tags_delete_title, tag.name),
            message = stringResource(R.string.tags_delete_message),
            confirm = stringResource(R.string.monitors_delete),
            onConfirm = {
                deleting = null
                actions.onDelete(tag.id)
            },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun Swatch(color: String) {
    Surface(shape = CircleShape, color = parseHexColor(color) ?: Color.Gray, modifier = Modifier.size(24.dp)) {}
}

@Composable
private fun TagEditDialog(
    initial: TagEdit,
    onSave: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initial.name) }
    var color by rememberSaveable { mutableStateOf(initial.color) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial.id == null) R.string.tags_new else R.string.tags_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.tags_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TagColorPicker(selected = color, onSelect = { color = it })
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim(), color) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.editor_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.monitors_cancel)) } },
    )
}

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun TagManagerPreview() {
    KumaTheme(dynamicColor = false) {
        TagManagerScreen(
            state =
                TagManagerUiState(
                    tags = listOf(Tag(1, "env", "#059669"), Tag(2, "team", "#7C3AED"), Tag(3, "critical", "#DC2626")),
                    refreshing = false,
                ),
            actions = TagManagerActions(),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun TagManagerEmptyPreview() {
    KumaTheme(dynamicColor = false) {
        TagManagerScreen(state = TagManagerUiState(refreshing = false), actions = TagManagerActions())
    }
}
