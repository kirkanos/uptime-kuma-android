package net.kirkanos.uptimekuma.feature.settings.server.infra

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.kirkanos.uptimekuma.core.ui.component.EmptyState
import net.kirkanos.uptimekuma.core.ui.component.LoadingState
import net.kirkanos.uptimekuma.feature.settings.R
import net.kirkanos.uptimekuma.feature.settings.server.common.AdminConfirmDialog
import net.kirkanos.uptimekuma.feature.settings.server.common.AdminFormDialog
import net.kirkanos.uptimekuma.feature.settings.server.common.AdminInfoCard
import net.kirkanos.uptimekuma.feature.settings.server.common.AdminMessageSnackbar
import net.kirkanos.uptimekuma.feature.settings.server.common.AdminScaffold
import net.kirkanos.uptimekuma.feature.settings.server.common.adminErrorText

/** How a list item is shown: title, subtitle, id, optional badge text. */
internal data class CrudRow(
    val id: Long,
    val title: String,
    val subtitle: String?,
    val badge: String? = null,
)

/** Texts of one CRUD screen. */
internal data class CrudTexts(
    val title: String,
    val emptyTitle: String,
    val emptyMessage: String,
    val addLabel: String,
    val createTitle: String,
    val editTitle: String,
    val info: String? = null,
)

/** Callbacks of [CrudListContent]. */
internal data class CrudActions(
    val onBack: () -> Unit = {},
    val onAdd: () -> Unit = {},
    val onEdit: (Long) -> Unit = {},
    val onDelete: (Long) -> Unit = {},
    val onSave: () -> Unit = {},
    val onTest: (() -> Unit)? = null,
    val onDismissEditor: () -> Unit = {},
    val onMessageShown: () -> Unit = {},
)

/**
 * Shared list screen: loading / empty states, rows with delete (confirmed), FAB to add, and the editor dialog
 * whose fields come from [editorFields].
 */
@Composable
internal fun <T, D> CrudListContent(
    state: CrudUiState<T, D>,
    texts: CrudTexts,
    icon: ImageVector,
    toRow: @Composable (T) -> CrudRow,
    canSave: (EditorState<D>) -> Boolean,
    actions: CrudActions,
    editorFields: @Composable (D) -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    var confirmDelete by rememberSaveable { mutableStateOf<Long?>(null) }
    AdminMessageSnackbar(state.message, snackbar, actions.onMessageShown)
    val rows = state.items?.map { toRow(it) }
    AdminScaffold(
        title = texts.title,
        onBack = actions.onBack,
        snackbarHostState = snackbar,
        floatingActionButton = {
            if (rows != null) {
                ExtendedFloatingActionButton(
                    onClick = actions.onAdd,
                    icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                    text = { Text(texts.addLabel) },
                )
            }
        },
    ) { modifier ->
        when {
            rows == null -> {
                LoadingState(modifier)
            }

            rows.isEmpty() -> {
                EmptyState(
                    title = texts.emptyTitle,
                    message = listOfNotNull(texts.emptyMessage, texts.info).joinToString("\n\n"),
                    icon = icon,
                    modifier = modifier,
                )
            }

            else -> {
                LazyColumn(modifier.fillMaxSize()) {
                    texts.info?.let { info -> item { AdminInfoCard(info) } }
                    items(rows, key = { it.id }) { row ->
                        CrudRowItem(row, icon, row.id in state.busyIds, { actions.onEdit(row.id) }) {
                            confirmDelete =
                                row.id
                        }
                    }
                }
            }
        }
    }
    state.editor?.let { editor ->
        EditorDialog(editor, texts, canSave(editor), actions) { editorFields(editor.draft) }
    }
    confirmDelete?.let { id ->
        val name = rows?.firstOrNull { it.id == id }?.title.orEmpty()
        AdminConfirmDialog(
            title = stringResource(R.string.admin_delete_title),
            message = stringResource(R.string.admin_delete_message, name),
            confirmLabel = stringResource(R.string.admin_delete),
            onConfirm = {
                confirmDelete = null
                actions.onDelete(id)
            },
            onDismiss = { confirmDelete = null },
        )
    }
}

@Composable
private fun CrudRowItem(
    row: CrudRow,
    icon: ImageVector,
    busy: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(row.title) },
        supportingContent = row.subtitle?.let { { Text(it) } },
        overlineContent = row.badge?.let { { Text(it, color = MaterialTheme.colorScheme.primary) } },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = {
            if (busy) {
                CircularProgressIndicator(Modifier.padding(12.dp))
            } else {
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = stringResource(R.string.admin_delete_named, row.title),
                    )
                }
            }
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun <D> EditorDialog(
    editor: EditorState<D>,
    texts: CrudTexts,
    canSave: Boolean,
    actions: CrudActions,
    fields: @Composable () -> Unit,
) {
    AdminFormDialog(
        title = if (editor.isNew) texts.createTitle else texts.editTitle,
        confirmLabel = stringResource(if (editor.saving) R.string.admin_saving else R.string.admin_save),
        confirmEnabled = canSave,
        onConfirm = actions.onSave,
        onDismiss = actions.onDismissEditor,
        extraButton = {
            actions.onTest?.let { onTest ->
                TextButton(onClick = onTest, enabled = canSave && editor.test != TestResult.Running) {
                    Text(stringResource(R.string.admin_test))
                }
            }
        },
    ) {
        fields()
        when (val test = editor.test) {
            null -> {}

            TestResult.Running -> {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            is TestResult.Ok -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        test.message ?: stringResource(R.string.admin_done_test_ok),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            is TestResult.Failed -> {
                Text(adminErrorText(test.error), color = MaterialTheme.colorScheme.error)
            }
        }
        editor.error?.let { Text(adminErrorText(it), color = MaterialTheme.colorScheme.error) }
    }
}
