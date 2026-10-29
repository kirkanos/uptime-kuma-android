package net.kirkanos.uptimekuma.feature.settings.server.about

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.kirkanos.uptimekuma.core.model.ServerDetails
import net.kirkanos.uptimekuma.core.model.ServerSettings
import net.kirkanos.uptimekuma.core.ui.theme.KumaTheme
import net.kirkanos.uptimekuma.feature.settings.R
import net.kirkanos.uptimekuma.feature.settings.server.common.AdminSectionHeader
import net.kirkanos.uptimekuma.feature.settings.server.common.AdminSwitchRow
import net.kirkanos.uptimekuma.feature.settings.server.settings.ServerSettingsUiState
import net.kirkanos.uptimekuma.feature.settings.server.settings.ServerSettingsViewModel
import net.kirkanos.uptimekuma.feature.settings.server.settings.SettingsFormScaffold
import net.kirkanos.uptimekuma.feature.settings.server.settings.previewSettingsState

private const val RELEASES_URL = "https://github.com/louislam/uptime-kuma/releases"

@Composable
internal fun AboutServerEntry(
    onBack: () -> Unit,
    viewModel: ServerSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AboutServerContent(state, onBack, viewModel::updateAndSave, viewModel::load, viewModel::consumeMessage)
}

@Composable
internal fun AboutServerContent(
    state: ServerSettingsUiState,
    onBack: () -> Unit,
    onToggle: ((ServerSettings) -> ServerSettings) -> Unit,
    onRetry: () -> Unit,
    onMessageShown: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val details = state.details ?: ServerDetails()
    SettingsFormScaffold(
        title = stringResource(R.string.admin_about_title),
        state = state,
        onBack = onBack,
        onSave = {},
        onRetry = onRetry,
        onMessageShown = onMessageShown,
        showSave = false,
    ) { draft ->
        InfoRow(Icons.Outlined.Dns, stringResource(R.string.admin_about_version), details.version ?: "–")
        ListItem(
            headlineContent = { Text(stringResource(R.string.admin_about_latest)) },
            supportingContent = { Text(details.latestVersion ?: stringResource(R.string.admin_about_latest_unknown)) },
            leadingContent = { Icon(Icons.Outlined.NewReleases, contentDescription = null) },
            trailingContent =
                if (details.updateAvailable) {
                    { Badge { Text(stringResource(R.string.admin_about_update_available)) } }
                } else {
                    null
                },
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.admin_about_release_notes)) },
            supportingContent = { Text(RELEASES_URL.removePrefix("https://")) },
            leadingContent = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null) },
            modifier = Modifier.clickable(role = Role.Button) { uriHandler.openUri(RELEASES_URL) },
        )
        val runtime = listOfNotNull(details.runtimePlatform, details.runtimeArch).joinToString(" / ")
        if (runtime.isNotEmpty()) InfoRow(Icons.Outlined.Memory, stringResource(R.string.admin_about_runtime), runtime)
        details.isContainer?.let { container ->
            InfoRow(
                Icons.Outlined.ViewInAr,
                stringResource(R.string.admin_about_container),
                stringResource(if (container) R.string.admin_yes else R.string.admin_no),
            )
        }
        details.dbType?.let { InfoRow(Icons.Outlined.Storage, stringResource(R.string.admin_about_database), it) }
        details.serverTimezone?.let { zone ->
            val text = listOfNotNull(zone, details.serverTimezoneOffset?.let { "UTC$it" }).joinToString(" · ")
            InfoRow(Icons.Outlined.Schedule, stringResource(R.string.admin_about_timezone), text)
        }

        AdminSectionHeader(stringResource(R.string.admin_about_updates))
        AdminSwitchRow(
            title = stringResource(R.string.admin_about_check_update),
            summary = stringResource(R.string.admin_about_check_update_summary),
            checked = draft.checkUpdate,
            enabled = !state.saving,
            onCheckedChange = { value -> onToggle { it.copy(checkUpdate = value) } },
        )
        AdminSwitchRow(
            title = stringResource(R.string.admin_about_check_beta),
            summary = stringResource(R.string.admin_about_check_beta_summary),
            checked = draft.checkBeta,
            enabled = !state.saving && draft.checkUpdate,
            onCheckedChange = { value -> onToggle { it.copy(checkBeta = value) } },
        )
    }
}

@Composable
private fun InfoRow(
    icon: ImageVector,
    title: String,
    value: String,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(value) },
        leadingContent = { Icon(icon, contentDescription = null) },
    )
}

@Preview(showBackground = true, heightDp = 1000)
@Composable
private fun AboutServerPreview() {
    KumaTheme(dynamicColor = false) {
        AboutServerContent(previewSettingsState, {}, {}, {}, {})
    }
}

@Preview(showBackground = true, heightDp = 1000, locale = "de", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun AboutServerDarkPreview() {
    KumaTheme(dynamicColor = false) {
        AboutServerContent(previewSettingsState, {}, {}, {}, {})
    }
}
