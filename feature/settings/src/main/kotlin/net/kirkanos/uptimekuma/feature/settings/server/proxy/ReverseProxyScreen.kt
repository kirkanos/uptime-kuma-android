package net.kirkanos.uptimekuma.feature.settings.server.proxy

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.kirkanos.uptimekuma.core.model.CloudflaredState
import net.kirkanos.uptimekuma.core.model.ServerSettings
import net.kirkanos.uptimekuma.core.ui.theme.KumaTheme
import net.kirkanos.uptimekuma.feature.settings.R
import net.kirkanos.uptimekuma.feature.settings.server.common.AdminFormDialog
import net.kirkanos.uptimekuma.feature.settings.server.common.AdminInfoCard
import net.kirkanos.uptimekuma.feature.settings.server.common.AdminMessageSnackbar
import net.kirkanos.uptimekuma.feature.settings.server.common.AdminRadioRow
import net.kirkanos.uptimekuma.feature.settings.server.common.AdminSectionHeader
import net.kirkanos.uptimekuma.feature.settings.server.common.SecretField
import net.kirkanos.uptimekuma.feature.settings.server.settings.ServerSettingsUiState
import net.kirkanos.uptimekuma.feature.settings.server.settings.ServerSettingsViewModel
import net.kirkanos.uptimekuma.feature.settings.server.settings.SettingsFormScaffold
import net.kirkanos.uptimekuma.feature.settings.server.settings.previewSettingsState

private const val REVERSE_PROXY_WIKI = "https://github.com/louislam/uptime-kuma/wiki/Reverse-Proxy"

@Composable
internal fun ReverseProxyEntry(
    onBack: () -> Unit,
    settingsViewModel: ServerSettingsViewModel = hiltViewModel(),
    tunnelViewModel: CloudflaredViewModel = hiltViewModel(),
) {
    val settings by settingsViewModel.state.collectAsStateWithLifecycle()
    val tunnel by tunnelViewModel.state.collectAsStateWithLifecycle()
    ReverseProxyContent(
        settings = settings,
        tunnel = tunnel,
        actions =
            ReverseProxyActions(
                onBack = onBack,
                onUpdate = settingsViewModel::update,
                onSave = settingsViewModel::save,
                onRetry = settingsViewModel::load,
                onSettingsMessageShown = settingsViewModel::consumeMessage,
                onToken = tunnelViewModel::setToken,
                onStart = tunnelViewModel::start,
                onStop = tunnelViewModel::stop,
                onRemoveToken = tunnelViewModel::removeToken,
                onTunnelMessageShown = tunnelViewModel::consumeMessage,
            ),
    )
}

internal data class ReverseProxyActions(
    val onBack: () -> Unit = {},
    val onUpdate: ((ServerSettings) -> ServerSettings) -> Unit = {},
    val onSave: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onSettingsMessageShown: () -> Unit = {},
    val onToken: (String) -> Unit = {},
    val onStart: () -> Unit = {},
    val onStop: (String?) -> Unit = {},
    val onRemoveToken: () -> Unit = {},
    val onTunnelMessageShown: () -> Unit = {},
)

@Composable
internal fun ReverseProxyContent(
    settings: ServerSettingsUiState,
    tunnel: CloudflaredUiState,
    actions: ReverseProxyActions,
) {
    val snackbar = remember { SnackbarHostState() }
    val uriHandler = LocalUriHandler.current
    var askStopPassword by rememberSaveable { mutableStateOf(false) }
    val authDisabled = settings.saved?.disableAuth == true
    AdminMessageSnackbar(tunnel.message, snackbar, actions.onTunnelMessageShown)
    SettingsFormScaffold(
        title = stringResource(R.string.admin_reverse_proxy_title),
        state = settings,
        onBack = actions.onBack,
        onSave = actions.onSave,
        onRetry = actions.onRetry,
        onMessageShown = actions.onSettingsMessageShown,
        snackbarHostState = snackbar,
    ) { draft ->
        AdminSectionHeader(stringResource(R.string.admin_cloudflared_title))
        CloudflaredSection(
            tunnel = tunnel,
            actions = actions,
            onStop = { if (authDisabled) actions.onStop(null) else askStopPassword = true },
        )

        HorizontalDivider(Modifier.padding(top = 8.dp))
        AdminSectionHeader(stringResource(R.string.admin_reverse_proxy_other))
        AdminInfoCard(stringResource(R.string.admin_reverse_proxy_other_info))
        TextButton(
            onClick = { uriHandler.openUri(REVERSE_PROXY_WIKI) },
            modifier = Modifier.padding(horizontal = 8.dp),
        ) {
            Text(stringResource(R.string.admin_reverse_proxy_wiki))
        }

        AdminSectionHeader(stringResource(R.string.admin_trust_proxy))
        Text(
            stringResource(R.string.admin_trust_proxy_summary),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        AdminRadioRow(
            title = stringResource(R.string.admin_yes),
            selected = draft.trustProxy,
            onClick = { actions.onUpdate { it.copy(trustProxy = true) } },
        )
        AdminRadioRow(
            title = stringResource(R.string.admin_no),
            selected = !draft.trustProxy,
            onClick = { actions.onUpdate { it.copy(trustProxy = false) } },
        )
    }
    if (askStopPassword) {
        StopTunnelDialog(
            onConfirm = { password ->
                askStopPassword = false
                actions.onStop(password)
            },
            onDismiss = { askStopPassword = false },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CloudflaredSection(
    tunnel: CloudflaredUiState,
    actions: ReverseProxyActions,
    onStop: () -> Unit,
) {
    val state = tunnel.tunnel
    when (state.installed) {
        null -> {
            CircularProgressIndicator(Modifier.padding(16.dp).size(24.dp))
            return
        }

        false -> {
            AdminInfoCard(stringResource(R.string.admin_cloudflared_not_installed))
            return
        }

        true -> {}
    }
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(
                if (state.running) R.string.admin_cloudflared_running else R.string.admin_cloudflared_stopped,
            ),
            style = MaterialTheme.typography.titleMedium,
            color =
                if (state.running) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        state.errorMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        SecretField(
            value = tunnel.tokenInput,
            onValueChange = actions.onToken,
            label = stringResource(R.string.admin_cloudflared_token),
            supportingText = stringResource(R.string.admin_cloudflared_token_hint),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.running) {
                Button(
                    onClick = onStop,
                    enabled = !tunnel.busy,
                ) { Text(stringResource(R.string.admin_cloudflared_stop)) }
            } else {
                Button(onClick = actions.onStart, enabled = tunnel.canStart) {
                    Text(stringResource(R.string.admin_cloudflared_start))
                }
            }
            if (state.token != null && !state.running) {
                OutlinedButton(onClick = actions.onRemoveToken, enabled = !tunnel.busy) {
                    Text(stringResource(R.string.admin_cloudflared_remove_token))
                }
            }
        }
    }
    AdminInfoCard(stringResource(R.string.admin_cloudflared_info))
}

@Composable
private fun StopTunnelDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by rememberSaveable { mutableStateOf("") }
    AdminFormDialog(
        title = stringResource(R.string.admin_cloudflared_stop),
        confirmLabel = stringResource(R.string.admin_cloudflared_stop),
        confirmEnabled = password.isNotEmpty(),
        onConfirm = { onConfirm(password) },
        onDismiss = onDismiss,
    ) {
        Text(stringResource(R.string.admin_cloudflared_stop_message))
        SecretField(password, { password = it }, stringResource(R.string.admin_current_password))
    }
}

private val previewTunnel =
    CloudflaredUiState(
        tunnel = CloudflaredState(installed = true, running = true, token = "eyJh...", message = "Connected"),
        tokenInput = "eyJh...",
    )

@Preview(showBackground = true, heightDp = 1200)
@Composable
private fun ReverseProxyPreview() {
    KumaTheme(dynamicColor = false) {
        ReverseProxyContent(previewSettingsState, previewTunnel, ReverseProxyActions())
    }
}

@Preview(showBackground = true, heightDp = 1000, locale = "de", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ReverseProxyNotInstalledPreview() {
    KumaTheme(dynamicColor = false) {
        ReverseProxyContent(
            previewSettingsState,
            CloudflaredUiState(tunnel = CloudflaredState(installed = false)),
            ReverseProxyActions(),
        )
    }
}
