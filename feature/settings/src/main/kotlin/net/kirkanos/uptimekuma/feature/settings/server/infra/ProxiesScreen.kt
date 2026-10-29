package net.kirkanos.uptimekuma.feature.settings.server.infra

import android.content.res.Configuration
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.kirkanos.uptimekuma.core.model.Proxy
import net.kirkanos.uptimekuma.core.model.ProxyDraft
import net.kirkanos.uptimekuma.core.model.ProxyProtocol
import net.kirkanos.uptimekuma.core.ui.theme.KumaTheme
import net.kirkanos.uptimekuma.feature.settings.R
import net.kirkanos.uptimekuma.feature.settings.server.common.SecretField

@Composable
internal fun ProxiesEntry(
    onBack: () -> Unit,
    viewModel: ProxiesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ProxiesContent(
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
                onDismissEditor = viewModel::dismissEditor,
                onMessageShown = viewModel::consumeMessage,
            ),
    )
}

@Composable
internal fun ProxiesContent(
    state: CrudUiState<Proxy, ProxyDraft>,
    canSave: (EditorState<ProxyDraft>) -> Boolean,
    onDraft: ((ProxyDraft) -> ProxyDraft) -> Unit,
    actions: CrudActions,
) {
    val defaultLabel = stringResource(R.string.admin_proxy_default)
    CrudListContent(
        state = state,
        texts =
            CrudTexts(
                title = stringResource(R.string.admin_proxies_title),
                emptyTitle = stringResource(R.string.admin_proxies_empty),
                emptyMessage = stringResource(R.string.admin_proxies_empty_message),
                addLabel = stringResource(R.string.admin_proxy_add),
                createTitle = stringResource(R.string.admin_proxy_add),
                editTitle = stringResource(R.string.admin_proxy_edit),
            ),
        icon = Icons.Outlined.Router,
        toRow = { proxy ->
            CrudRow(
                id = proxy.id,
                title = proxy.label,
                subtitle = if (proxy.auth) stringResource(R.string.admin_proxy_auth_as, proxy.username) else null,
                badge = defaultLabel.takeIf { proxy.isDefault },
            )
        },
        canSave = canSave,
        actions = actions,
    ) { draft -> ProxyFields(draft, onDraft) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProxyFields(
    draft: ProxyDraft,
    onDraft: ((ProxyDraft) -> ProxyDraft) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = draft.protocol.wire.uppercase(),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.admin_proxy_protocol)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ProxyProtocol.entries.forEach { protocol ->
                DropdownMenuItem(
                    text = { Text(protocol.wire.uppercase()) },
                    onClick = {
                        expanded = false
                        onDraft { it.copy(protocol = protocol) }
                    },
                )
            }
        }
    }
    OutlinedTextField(
        value = draft.host,
        onValueChange = { v -> onDraft { it.copy(host = v) } },
        label = { Text(stringResource(R.string.admin_proxy_host)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = draft.port?.toString().orEmpty(),
        onValueChange = { v -> onDraft { it.copy(port = v.filter(Char::isDigit).take(5).toIntOrNull()) } },
        label = { Text(stringResource(R.string.admin_proxy_port)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
    ToggleRow(
        stringResource(R.string.admin_proxy_auth),
        draft.auth,
        switch = true,
    ) { v -> onDraft { it.copy(auth = v) } }
    if (draft.auth) {
        OutlinedTextField(
            value = draft.username,
            onValueChange = { v -> onDraft { it.copy(username = v) } },
            label = { Text(stringResource(R.string.admin_proxy_username)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        SecretField(
            draft.password,
            { v -> onDraft { it.copy(password = v) } },
            stringResource(R.string.admin_proxy_password),
        )
    }
    ToggleRow(
        stringResource(R.string.admin_proxy_set_default),
        draft.isDefault,
    ) { v -> onDraft { it.copy(isDefault = v) } }
    Text(
        stringResource(R.string.admin_proxy_default_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    ToggleRow(stringResource(R.string.admin_proxy_apply_existing), draft.applyExisting) { v ->
        onDraft { it.copy(applyExisting = v) }
    }
}

@Composable
internal fun ToggleRow(
    label: String,
    checked: Boolean,
    switch: Boolean = false,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = if (switch) Role.Switch else Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (switch) {
            Text(label, Modifier.weight(1f))
            Switch(checked = checked, onCheckedChange = null)
        } else {
            Checkbox(checked = checked, onCheckedChange = null)
            Text(label, Modifier.weight(1f))
        }
    }
}

private val previewProxies =
    CrudUiState<Proxy, ProxyDraft>(
        items =
            listOf(
                Proxy(1, ProxyProtocol.HTTPS, "proxy.example", 3128, auth = true, username = "me", isDefault = true),
                Proxy(2, ProxyProtocol.SOCKS5, "10.0.0.2", 1080, auth = false),
            ),
    )

@Preview(showBackground = true)
@Composable
private fun ProxiesPreview() {
    KumaTheme(dynamicColor = false) {
        ProxiesContent(previewProxies, { true }, {}, CrudActions())
    }
}

@Preview(showBackground = true, locale = "de", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ProxyEditorPreview() {
    KumaTheme(dynamicColor = false) {
        ProxiesContent(
            previewProxies.copy(
                editor = EditorState(ProxyDraft(host = "proxy", port = 8080, auth = true), isNew = true),
            ),
            { true },
            {},
            CrudActions(),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ProxiesEmptyPreview() {
    KumaTheme(dynamicColor = false) {
        ProxiesContent(CrudUiState(items = emptyList()), { true }, {}, CrudActions())
    }
}
