package net.kirkanos.uptimekuma.feature.monitors.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.kirkanos.uptimekuma.core.domain.editor.MonitorDrafts
import net.kirkanos.uptimekuma.core.domain.editor.MonitorSpec
import net.kirkanos.uptimekuma.core.domain.editor.MonitorValidator
import net.kirkanos.uptimekuma.core.model.EditorPickers
import net.kirkanos.uptimekuma.core.model.KumaError
import net.kirkanos.uptimekuma.core.model.Monitor
import net.kirkanos.uptimekuma.core.model.MonitorTag
import net.kirkanos.uptimekuma.core.model.PickerOption
import net.kirkanos.uptimekuma.core.model.Tag
import net.kirkanos.uptimekuma.core.ui.component.ErrorState
import net.kirkanos.uptimekuma.core.ui.component.LoadingState
import net.kirkanos.uptimekuma.core.ui.theme.KumaTheme
import net.kirkanos.uptimekuma.feature.monitors.R

/** Callbacks of [MonitorEditorScreen]. */
@Suppress("LongParameterList")
@Immutable
internal class EditorActions(
    val onClose: () -> Unit = {},
    val onSave: (confirmed: Boolean) -> Unit = {},
    val onFieldChange: (String, Any?) -> Unit = { _, _ -> },
    val onRegeneratePushToken: () -> Unit = {},
    val onAddTag: (Tag, String) -> Unit = { _, _ -> },
    val onCreateTag: (name: String, color: String, value: String) -> Unit = { _, _, _ -> },
    val onRemoveTag: (MonitorTag) -> Unit = {},
    val onManageTags: () -> Unit = {},
    val onDelete: (deleteChildren: Boolean) -> Unit = {},
    val onDismissShortInterval: () -> Unit = {},
    val onDismissProblem: () -> Unit = {},
    val onRetryLoad: () -> Unit = {},
)

@Composable
internal fun MonitorEditorEntry(
    onClose: () -> Unit,
    onSaved: (Long) -> Unit,
    onDeleted: () -> Unit,
    onManageTags: () -> Unit,
    viewModel: MonitorEditorViewModel = hiltViewModel(),
) {
    val cloneFormat = stringResource(R.string.editor_clone_name, "%s")
    LaunchedEffect(viewModel) { viewModel.load(cloneFormat) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.savedMonitorId) { state.savedMonitorId?.let(onSaved) }
    LaunchedEffect(state.deleted) { if (state.deleted) onDeleted() }
    val actions =
        remember(viewModel, onClose, onManageTags) {
            EditorActions(
                onClose = onClose,
                onSave = viewModel::save,
                onFieldChange = viewModel::onFieldChange,
                onRegeneratePushToken = viewModel::regeneratePushToken,
                onAddTag = viewModel::addTag,
                onCreateTag = viewModel::createTag,
                onRemoveTag = viewModel::removeTag,
                onManageTags = onManageTags,
                onDelete = viewModel::delete,
                onDismissShortInterval = viewModel::dismissShortIntervalConfirm,
                onDismissProblem = viewModel::dismissProblem,
                onRetryLoad = { viewModel.retryLoad(cloneFormat) },
            )
        }
    MonitorEditorScreen(state = state, actions = actions)
}

private enum class EditorDialog { DISCARD, DELETE, TYPE, ADD_TAG }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MonitorEditorScreen(
    state: MonitorEditorUiState,
    actions: EditorActions,
    modifier: Modifier = Modifier,
) {
    var dialog by rememberSaveable { mutableStateOf<EditorDialog?>(null) }
    val close = {
        if (state.dirty &&
            state.savedMonitorId == null
        ) {
            dialog = EditorDialog.DISCARD
        } else {
            actions.onClose()
        }
    }
    BackHandler(enabled = state.dirty && state.savedMonitorId == null) { dialog = EditorDialog.DISCARD }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(state.mode.titleRes())) },
                navigationIcon = {
                    IconButton(onClick = close) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.editor_close))
                    }
                },
                actions = {
                    if (state.saving || state.deleting) {
                        CircularProgressIndicator(modifier = Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
                    } else if (state.draft != null) {
                        TextButton(onClick = { actions.onSave(false) }) { Text(stringResource(R.string.editor_save)) }
                    }
                    if (state.mode == EditorMode.EDIT && state.draft != null) {
                        OverflowMenu(
                            onDelete = { dialog = EditorDialog.DELETE },
                            enabled =
                                !state.saving && !state.deleting,
                        )
                    }
                },
            )
        },
    ) { padding ->
        val contentModifier = Modifier.padding(padding).fillMaxSize()
        when {
            state.loading -> {
                LoadingState(contentModifier)
            }

            state.loadError != null -> {
                ErrorState(state.loadError, contentModifier, onRetry = actions.onRetryLoad)
            }

            else -> {
                EditorForm(
                    state = state,
                    actions = actions,
                    onChangeType = { dialog = EditorDialog.TYPE },
                    onAddTag = { dialog = EditorDialog.ADD_TAG },
                    modifier = contentModifier,
                )
            }
        }
    }

    EditorDialogs(state, actions, dialog, onDismiss = { dialog = null })
}

@Composable
private fun EditorDialogs(
    state: MonitorEditorUiState,
    actions: EditorActions,
    dialog: EditorDialog?,
    onDismiss: () -> Unit,
) {
    when (dialog) {
        EditorDialog.DISCARD -> {
            ConfirmDialog(
                title = stringResource(R.string.editor_discard_title),
                message = stringResource(R.string.editor_discard_message),
                confirm = stringResource(R.string.editor_discard),
                onConfirm = {
                    onDismiss()
                    actions.onClose()
                },
                onDismiss = onDismiss,
            )
        }

        EditorDialog.DELETE -> {
            DeleteDialog(
                name = state.draft?.name.orEmpty(),
                isGroup = state.type == Monitor.TYPE_GROUP,
                onConfirm = { children ->
                    onDismiss()
                    actions.onDelete(children)
                },
                onDismiss = onDismiss,
            )
        }

        EditorDialog.TYPE -> {
            TypeDialog(
                selected = state.type,
                onSelect = {
                    onDismiss()
                    actions.onFieldChange("type", it)
                },
                onDismiss = onDismiss,
            )
        }

        EditorDialog.ADD_TAG -> {
            AddTagDialog(
                tags = state.pickers.tags,
                onAdd = { tag, value ->
                    onDismiss()
                    actions.onAddTag(tag, value)
                },
                onCreate = { name, color, value ->
                    onDismiss()
                    actions.onCreateTag(name, color, value)
                },
                onDismiss = onDismiss,
            )
        }

        null -> {
            Unit
        }
    }
    if (state.confirmShortInterval) {
        ConfirmDialog(
            title = stringResource(R.string.editor_short_interval_title),
            message =
                pluralStringResource(
                    R.plurals.editor_short_interval_message,
                    MonitorValidator.SHORT_INTERVAL_SECONDS.toInt(),
                    MonitorValidator.SHORT_INTERVAL_SECONDS.toInt(),
                ),
            confirm = stringResource(R.string.editor_save_anyway),
            onConfirm = { actions.onSave(true) },
            onDismiss = actions.onDismissShortInterval,
        )
    }
}

private fun EditorMode.titleRes(): Int =
    when (this) {
        EditorMode.NEW -> R.string.editor_title_new
        EditorMode.EDIT -> R.string.editor_title_edit
        EditorMode.CLONE -> R.string.editor_title_clone
    }

@Composable
private fun OverflowMenu(
    onDelete: () -> Unit,
    enabled: Boolean,
) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.monitors_more_actions))
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.monitors_delete)) },
            leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
            enabled = enabled,
            onClick = {
                open = false
                onDelete()
            },
        )
    }
}

@Composable
private fun EditorForm(
    state: MonitorEditorUiState,
    actions: EditorActions,
    onChangeType: () -> Unit,
    onAddTag: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val draft = state.draft ?: return
    val spec = MonitorSpec.bundled
    val typeSpec = spec.type(draft.type)
    val ctx =
        remember(draft, state.pickers, state.pushUrl) {
            FieldContext(
                values = draft.fields,
                pickers = state.pickers,
                excludedParents = state.excludedParents,
                pushUrl = state.pushUrl,
                onChange = actions.onFieldChange,
                onRegeneratePushToken = actions.onRegeneratePushToken,
            )
        }
    val errors = state.visibleErrors()
    LazyColumn(modifier = modifier, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp)) {
        state.problem?.let { problem ->
            item(key = "problem") { ProblemBanner(problem, actions.onDismissProblem) }
        }
        item(key = "type") {
            ListItem(
                headlineContent = { Text(typeSpec?.let { typeLabel(it) } ?: draft.type) },
                overlineContent = { Text(stringResource(R.string.editor_monitor_type)) },
                trailingContent = {
                    Text(
                        stringResource(R.string.editor_change),
                        color = MaterialTheme.colorScheme.primary,
                    )
                },
                modifier =
                    Modifier.clickable(
                        onClickLabel = stringResource(R.string.editor_change_type),
                        onClick = onChangeType,
                    ),
            )
        }
        state.sections.forEach { section ->
            item(key = "section-${section.kind}") { SectionHeader(sectionTitle(section.kind, typeSpec)) }
            if (section.kind == SectionKind.TAGS) {
                item(key = "tags") { TagsSection(draft.tags, onAddTag, actions.onRemoveTag, actions.onManageTags) }
            }
            items(section.fields, key = { it.name }) { field ->
                EditorField(
                    field = field,
                    issue = errors[field.name],
                    ctx = ctx,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Column(Modifier.padding(top = 20.dp, bottom = 4.dp)) {
        HorizontalDivider()
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 12.dp).semantics { heading() },
        )
    }
}

@Composable
private fun ProblemBanner(
    problem: EditorProblem,
    onDismiss: () -> Unit,
) {
    val text =
        when (problem) {
            EditorProblem.Invalid -> stringResource(R.string.editor_problem_invalid)
            is EditorProblem.Server -> editorErrorText(problem.error)
            is EditorProblem.TagsFailed -> stringResource(R.string.editor_problem_tags, editorErrorText(problem.error))
        }
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (problem is EditorProblem.TagsFailed) Icons.Outlined.Warning else Icons.Outlined.ErrorOutline,
                contentDescription = null,
            )
            Text(text, modifier = Modifier.weight(1f).padding(12.dp), style = MaterialTheme.typography.bodyMedium)
            IconButton(onClick = onDismiss) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.editor_dismiss))
            }
        }
    }
}

@Composable
internal fun ConfirmDialog(
    title: String,
    message: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.monitors_cancel)) } },
    )
}

@Composable
private fun DeleteDialog(
    name: String,
    isGroup: Boolean,
    onConfirm: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var children by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
        title = { Text(stringResource(R.string.monitors_delete_title, name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.monitors_delete_message))
                if (isGroup) {
                    Row(
                        Modifier.toggleable(value = children, role = Role.Checkbox) { children = it },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = children, onCheckedChange = null)
                        Text(stringResource(R.string.editor_delete_children), modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(children) },
            ) { Text(stringResource(R.string.monitors_delete)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.monitors_cancel)) } },
    )
}

@Composable
private fun TypeDialog(
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_type_picker_title)) },
        text = { TypeList(selected = selected, onTypeSelected = onSelect, modifier = Modifier.height(480.dp)) },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.monitors_cancel)) } },
    )
}

@Preview(showBackground = true, heightDp = 1600)
@Preview(showBackground = true, heightDp = 1600, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun MonitorEditorScreenPreview() {
    val spec = MonitorSpec.bundled
    val draft =
        MonitorDrafts
            .newDraft(spec, "http", notificationIds = listOf(1))
            .with("name", "Website")
            .with("url", "https://example.com")
            .with("authMethod", "basic")
            .copy(tags = listOf(MonitorTag(1, "env", "#059669", "prod")))
    KumaTheme(dynamicColor = false) {
        MonitorEditorScreen(
            state =
                MonitorEditorUiState(
                    loading = false,
                    draft = draft,
                    original = draft,
                    sections = EditorSections.build(spec, draft.fields),
                    pickers =
                        EditorPickers(
                            notifications = listOf(PickerOption(1, "ntfy phone", true), PickerOption(2, "Mail")),
                            proxies = listOf(PickerOption(5, "http://proxy.lan:3128")),
                            groups = listOf(PickerOption(11, "Web")),
                        ),
                    problem =
                        EditorProblem.Server(
                            KumaError(KumaError.Kind.SERVER_REJECTED, "Interval cannot be less than 1 seconds"),
                        ),
                ),
            actions = EditorActions(),
        )
    }
}

@Preview(showBackground = true, heightDp = 900)
@Composable
private fun PushEditorPreview() {
    val spec = MonitorSpec.bundled
    val draft = MonitorDrafts.newDraft(spec, "push").with("name", "Backup job")
    KumaTheme(dynamicColor = false) {
        MonitorEditorScreen(
            state =
                MonitorEditorUiState(
                    mode = EditorMode.EDIT,
                    loading = false,
                    draft = draft,
                    original = draft,
                    sections = EditorSections.build(spec, draft.fields),
                    baseUrl = "https://uptime.example.org",
                ),
            actions = EditorActions(),
        )
    }
}
