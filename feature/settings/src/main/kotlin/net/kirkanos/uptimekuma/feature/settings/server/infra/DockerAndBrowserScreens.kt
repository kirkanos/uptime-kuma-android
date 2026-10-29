package net.kirkanos.uptimekuma.feature.settings.server.infra

import android.content.res.Configuration
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.kirkanos.uptimekuma.core.model.DockerHost
import net.kirkanos.uptimekuma.core.model.DockerHostDraft
import net.kirkanos.uptimekuma.core.model.DockerHostType
import net.kirkanos.uptimekuma.core.model.KumaError
import net.kirkanos.uptimekuma.core.model.RemoteBrowser
import net.kirkanos.uptimekuma.core.model.RemoteBrowserDraft
import net.kirkanos.uptimekuma.core.ui.theme.KumaTheme
import net.kirkanos.uptimekuma.feature.settings.R

@Composable
internal fun DockerHostsEntry(
    onBack: () -> Unit,
    viewModel: DockerHostsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    DockerHostsContent(
        state = state,
        canSave = viewModel::canSave,
        onDraft = viewModel::updateDraft,
        actions =
            CrudActions(
                onBack = onBack,
                onAdd = viewModel::create,
                onEdit = { id -> state.items?.firstOrNull { it.id == id }?.let(viewModel::edit) },
                onDelete = viewModel::delete,
                onSave = viewModel::save,
                onTest = viewModel::runTest,
                onDismissEditor = viewModel::dismissEditor,
                onMessageShown = viewModel::consumeMessage,
            ),
    )
}

@Composable
internal fun DockerHostsContent(
    state: CrudUiState<DockerHost, DockerHostDraft>,
    canSave: (EditorState<DockerHostDraft>) -> Boolean,
    onDraft: ((DockerHostDraft) -> DockerHostDraft) -> Unit,
    actions: CrudActions,
) {
    CrudListContent(
        state = state,
        texts =
            CrudTexts(
                title = stringResource(R.string.admin_docker_title),
                emptyTitle = stringResource(R.string.admin_docker_empty),
                emptyMessage = stringResource(R.string.admin_docker_empty_message),
                addLabel = stringResource(R.string.admin_docker_add),
                createTitle = stringResource(R.string.admin_docker_add),
                editTitle = stringResource(R.string.admin_docker_edit),
            ),
        icon = Icons.Outlined.ViewInAr,
        toRow = { host -> CrudRow(host.id, host.name, host.daemon, badge = host.type.label()) },
        canSave = canSave,
        actions = actions,
    ) { draft -> DockerHostFields(draft, onDraft) }
}

@Composable
private fun DockerHostType.label(): String =
    stringResource(
        if (this ==
            DockerHostType.SOCKET
        ) {
            R.string.admin_docker_type_socket
        } else {
            R.string.admin_docker_type_tcp
        },
    )

@Composable
private fun DockerHostFields(
    draft: DockerHostDraft,
    onDraft: ((DockerHostDraft) -> DockerHostDraft) -> Unit,
) {
    OutlinedTextField(
        value = draft.name,
        onValueChange = { v -> onDraft { it.copy(name = v) } },
        label = { Text(stringResource(R.string.admin_name)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(stringResource(R.string.admin_docker_connection_type))
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        DockerHostType.entries.forEachIndexed { index, type ->
            SegmentedButton(
                selected = draft.type == type,
                onClick = {
                    onDraft {
                        val daemon =
                            when {
                                it.type == type -> it.daemon
                                type == DockerHostType.SOCKET -> DockerHostDraft.DEFAULT_SOCKET
                                else -> ""
                            }
                        it.copy(type = type, daemon = daemon)
                    }
                },
                shape = SegmentedButtonDefaults.itemShape(index, DockerHostType.entries.size),
            ) { Text(type.label()) }
        }
    }
    OutlinedTextField(
        value = draft.daemon,
        onValueChange = { v -> onDraft { it.copy(daemon = v) } },
        label = { Text(stringResource(R.string.admin_docker_daemon)) },
        placeholder = {
            Text(if (draft.type == DockerHostType.SOCKET) DockerHostDraft.DEFAULT_SOCKET else "tcp://localhost:2375")
        },
        supportingText = {
            Text(
                stringResource(
                    if (draft.type ==
                        DockerHostType.SOCKET
                    ) {
                        R.string.admin_docker_socket_hint
                    } else {
                        R.string.admin_docker_tcp_hint
                    },
                ),
            )
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun RemoteBrowsersEntry(
    onBack: () -> Unit,
    viewModel: RemoteBrowsersViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    RemoteBrowsersContent(
        state = state,
        canSave = viewModel::canSave,
        onDraft = viewModel::updateDraft,
        actions =
            CrudActions(
                onBack = onBack,
                onAdd = viewModel::create,
                onEdit = { id -> state.items?.firstOrNull { it.id == id }?.let(viewModel::edit) },
                onDelete = viewModel::delete,
                onSave = viewModel::save,
                onTest = viewModel::runTest,
                onDismissEditor = viewModel::dismissEditor,
                onMessageShown = viewModel::consumeMessage,
            ),
    )
}

@Composable
internal fun RemoteBrowsersContent(
    state: CrudUiState<RemoteBrowser, RemoteBrowserDraft>,
    canSave: (EditorState<RemoteBrowserDraft>) -> Boolean,
    onDraft: ((RemoteBrowserDraft) -> RemoteBrowserDraft) -> Unit,
    actions: CrudActions,
) {
    CrudListContent(
        state = state,
        texts =
            CrudTexts(
                title = stringResource(R.string.admin_browsers_title),
                emptyTitle = stringResource(R.string.admin_browsers_empty),
                emptyMessage = stringResource(R.string.admin_browsers_empty_message),
                addLabel = stringResource(R.string.admin_browser_add),
                createTitle = stringResource(R.string.admin_browser_add),
                editTitle = stringResource(R.string.admin_browser_edit),
            ),
        icon = Icons.Outlined.Language,
        toRow = { browser -> CrudRow(browser.id, browser.name, browser.url) },
        canSave = canSave,
        actions = actions,
    ) { draft ->
        OutlinedTextField(
            value = draft.name,
            onValueChange = { v -> onDraft { it.copy(name = v) } },
            label = { Text(stringResource(R.string.admin_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = draft.url,
            onValueChange = { v -> onDraft { it.copy(url = v) } },
            label = { Text(stringResource(R.string.admin_browser_url)) },
            placeholder = { Text("ws://chrome.local:3000/playwright/chromium") },
            supportingText = { Text(stringResource(R.string.admin_browser_url_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun DockerHostsPreview() {
    KumaTheme(dynamicColor = false) {
        DockerHostsContent(
            CrudUiState(
                items =
                    listOf(
                        DockerHost(1, "Local", DockerHostType.SOCKET, DockerHostDraft.DEFAULT_SOCKET),
                        DockerHost(2, "NAS", DockerHostType.TCP, "tcp://nas:2375"),
                    ),
            ),
            { true },
            {},
            CrudActions(),
        )
    }
}

@Preview(showBackground = true, locale = "de", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun DockerHostEditorPreview() {
    KumaTheme(dynamicColor = false) {
        DockerHostsContent(
            CrudUiState(
                items = emptyList(),
                editor =
                    EditorState(
                        DockerHostDraft(name = "Local"),
                        isNew = true,
                        test =
                            TestResult.Failed(
                                KumaError(KumaError.Kind.SERVER_REJECTED, "connect ENOENT /var/run/docker.sock"),
                            ),
                    ),
            ),
            { true },
            {},
            CrudActions(onTest = {}),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RemoteBrowsersPreview() {
    KumaTheme(dynamicColor = false) {
        RemoteBrowsersContent(
            CrudUiState(items = listOf(RemoteBrowser(1, "Browserless", "ws://browserless:3000"))),
            { true },
            {},
            CrudActions(),
        )
    }
}

@Preview(showBackground = true, locale = "de", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RemoteBrowsersEmptyPreview() {
    KumaTheme(dynamicColor = false) {
        RemoteBrowsersContent(CrudUiState(items = emptyList()), { true }, {}, CrudActions())
    }
}
