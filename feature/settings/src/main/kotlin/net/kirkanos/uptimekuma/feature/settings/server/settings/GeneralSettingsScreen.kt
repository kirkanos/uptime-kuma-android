package net.kirkanos.uptimekuma.feature.settings.server.settings

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.kirkanos.uptimekuma.core.model.EntryPage
import net.kirkanos.uptimekuma.core.model.ServerDetails
import net.kirkanos.uptimekuma.core.model.ServerSettings
import net.kirkanos.uptimekuma.core.ui.theme.KumaTheme
import net.kirkanos.uptimekuma.feature.settings.R
import net.kirkanos.uptimekuma.feature.settings.server.common.AdminInfoCard
import net.kirkanos.uptimekuma.feature.settings.server.common.AdminRadioRow
import net.kirkanos.uptimekuma.feature.settings.server.common.AdminSectionHeader
import net.kirkanos.uptimekuma.feature.settings.server.common.AdminSwitchRow
import net.kirkanos.uptimekuma.feature.settings.server.common.SecretField
import net.kirkanos.uptimekuma.feature.settings.server.common.adminErrorText
import java.time.ZoneId

@Composable
internal fun GeneralSettingsEntry(
    onBack: () -> Unit,
    viewModel: ServerSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    GeneralSettingsContent(
        state = state,
        onBack = onBack,
        onUpdate = viewModel::update,
        onSave = viewModel::save,
        onRetry = viewModel::load,
        onTestChrome = viewModel::testChrome,
        onMessageShown = viewModel::consumeMessage,
    )
}

@Composable
internal fun GeneralSettingsContent(
    state: ServerSettingsUiState,
    onBack: () -> Unit,
    onUpdate: ((ServerSettings) -> ServerSettings) -> Unit,
    onSave: () -> Unit,
    onRetry: () -> Unit,
    onTestChrome: () -> Unit,
    onMessageShown: () -> Unit,
) {
    var pickZone by rememberSaveable { mutableStateOf(false) }
    SettingsFormScaffold(
        title = stringResource(R.string.admin_general_title),
        state = state,
        onBack = onBack,
        onSave = onSave,
        onRetry = onRetry,
        onMessageShown = onMessageShown,
    ) { draft ->
        AdminSectionHeader(stringResource(R.string.admin_general_timezone))
        ListItem(
            headlineContent = { Text(stringResource(R.string.admin_general_server_timezone)) },
            supportingContent = {
                val offset =
                    state.details?.serverTimezoneOffset?.takeIf {
                        draft.serverTimezone ==
                            state.saved?.serverTimezone
                    }
                Text(listOfNotNull(draft.serverTimezone.ifBlank { "–" }, offset?.let { "UTC$it" }).joinToString(" · "))
            },
            leadingContent = { Icon(Icons.Outlined.Schedule, contentDescription = null) },
            modifier = Modifier.clickable { pickZone = true },
        )
        Text(
            stringResource(R.string.admin_general_display_timezone_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        AdminSectionHeader(stringResource(R.string.admin_general_primary_url))
        PrimaryUrlField(draft, state, onUpdate)

        AdminSectionHeader(stringResource(R.string.admin_general_search_engine))
        AdminRadioRow(
            title = stringResource(R.string.admin_general_search_allow),
            selected = draft.searchEngineIndex,
            onClick = { onUpdate { it.copy(searchEngineIndex = true) } },
        )
        AdminRadioRow(
            title = stringResource(R.string.admin_general_search_discourage),
            selected = !draft.searchEngineIndex,
            onClick = { onUpdate { it.copy(searchEngineIndex = false) } },
        )

        AdminSectionHeader(stringResource(R.string.admin_general_entry_page))
        EntryPageOptions(draft.entryPage, state.statusPages) { page -> onUpdate { it.copy(entryPage = page) } }

        AdminSectionHeader(stringResource(R.string.admin_general_integrations))
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SecretField(
                value = draft.steamApiKey,
                onValueChange = { value -> onUpdate { it.copy(steamApiKey = value) } },
                label = stringResource(R.string.admin_general_steam_key),
                supportingText = stringResource(R.string.admin_general_steam_key_hint),
            )
            SecretField(
                value = draft.globalpingApiToken,
                onValueChange = { value -> onUpdate { it.copy(globalpingApiToken = value) } },
                label = stringResource(R.string.admin_general_globalping_token),
            )
        }

        if (state.details?.isContainer != false) {
            AdminSwitchRow(
                title = stringResource(R.string.admin_general_nscd),
                summary = stringResource(R.string.admin_general_nscd_summary),
                checked = draft.nscd,
                onCheckedChange = { value -> onUpdate { it.copy(nscd = value) } },
            )
        }

        AdminSectionHeader(stringResource(R.string.admin_general_chrome))
        ChromeField(draft, state.chromeTest, onUpdate, onTestChrome)
    }
    if (pickZone) {
        state.draft?.let { draft ->
            TimezonePickerDialog(
                current = draft.serverTimezone,
                onPick = { zone ->
                    pickZone = false
                    onUpdate { it.copy(serverTimezone = zone) }
                },
                onDismiss = { pickZone = false },
            )
        }
    }
}

@Composable
private fun PrimaryUrlField(
    draft: ServerSettings,
    state: ServerSettingsUiState,
    onUpdate: ((ServerSettings) -> ServerSettings) -> Unit,
) {
    val invalid = SettingsProblem.PRIMARY_URL in state.problems
    Column(Modifier.padding(horizontal = 16.dp)) {
        OutlinedTextField(
            value = draft.primaryBaseUrl,
            onValueChange = { value -> onUpdate { it.copy(primaryBaseUrl = value) } },
            label = { Text(stringResource(R.string.admin_general_primary_url)) },
            placeholder = { Text("https://") },
            singleLine = true,
            isError = invalid,
            supportingText = {
                Text(
                    stringResource(
                        if (invalid) {
                            R.string.admin_general_primary_url_invalid
                        } else {
                            R.string.admin_general_primary_url_hint
                        },
                    ),
                )
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        val appUrl = state.appServerUrl
        if (appUrl != null && appUrl != draft.primaryBaseUrl.trim()) {
            TextButton(onClick = { onUpdate { it.copy(primaryBaseUrl = appUrl) } }) {
                Text(stringResource(R.string.admin_general_primary_url_use_app, appUrl))
            }
        }
    }
    AdminInfoCard(stringResource(R.string.admin_general_primary_url_app_info))
}

@Composable
private fun EntryPageOptions(
    selected: String,
    statusPages: List<EntryPageOption>,
    onSelect: (String) -> Unit,
) {
    AdminRadioRow(
        title = stringResource(R.string.admin_general_entry_dashboard),
        selected = selected == EntryPage.DASHBOARD,
        onClick = { onSelect(EntryPage.DASHBOARD) },
    )
    statusPages.forEach { page ->
        val value = EntryPage.statusPage(page.slug)
        AdminRadioRow(
            title = stringResource(R.string.admin_general_entry_status_page, page.title),
            summary = "/status/${page.slug}",
            selected = selected == value,
            onClick = { onSelect(value) },
        )
    }
    // A status page that no longer exists stays visible so the user notices it.
    val unknownSlug = EntryPage.slugOf(selected)?.takeIf { slug -> statusPages.none { it.slug == slug } }
    if (unknownSlug != null) {
        AdminRadioRow(title = "/status/$unknownSlug", selected = true, onClick = {})
    }
}

@Composable
private fun ChromeField(
    draft: ServerSettings,
    test: ChromeTest,
    onUpdate: ((ServerSettings) -> ServerSettings) -> Unit,
    onTest: () -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = draft.chromeExecutable,
            onValueChange = { value -> onUpdate { it.copy(chromeExecutable = value) } },
            label = { Text(stringResource(R.string.admin_general_chrome_executable)) },
            placeholder = { Text("/usr/bin/chromium") },
            supportingText = { Text(stringResource(R.string.admin_general_chrome_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onTest, enabled = test != ChromeTest.Running) {
                Text(stringResource(R.string.admin_test))
            }
            when (test) {
                ChromeTest.Idle -> {}

                ChromeTest.Running -> {
                    CircularProgressIndicator(Modifier.padding(4.dp))
                }

                is ChromeTest.Found -> {
                    Text(stringResource(R.string.admin_general_chrome_found, test.version ?: "?"))
                }

                is ChromeTest.Failed -> {
                    Text(adminErrorText(test.error), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/** Searchable list of IANA zones; "Auto" picks the device's zone like the web UI's guess. */
@Composable
internal fun TimezonePickerDialog(
    current: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val zones = remember { ZoneId.getAvailableZoneIds().filter { '/' in it || it == "UTC" }.sorted() }
    val deviceZone = remember { ZoneId.systemDefault().id }
    val filtered = remember(query) { zones.filter { it.contains(query.trim().replace(' ', '_'), ignoreCase = true) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.admin_general_server_timezone)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.admin_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    if (query.isBlank()) {
                        item {
                            AdminRadioRow(
                                title = stringResource(R.string.admin_general_timezone_auto, deviceZone),
                                selected = false,
                                onClick = { onPick(deviceZone) },
                            )
                        }
                    }
                    items(filtered, key = { it }) { zone ->
                        AdminRadioRow(
                            title = zone.replace('_', ' '),
                            selected = zone == current,
                            onClick = { onPick(zone) },
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) } },
    )
}

internal val previewSettingsState =
    ServerSettingsUiState(
        saved = ServerSettings(serverTimezone = "Europe/Berlin"),
        draft = ServerSettings(serverTimezone = "Europe/Berlin", primaryBaseUrl = "https://status.example"),
        details =
            ServerDetails(
                version = "2.5.8",
                latestVersion = "2.6.0",
                serverTimezone = "Europe/Berlin",
                serverTimezoneOffset = "+01:00",
                isContainer = true,
                dbType = "sqlite",
                runtimePlatform = "linux",
                runtimeArch = "arm64",
            ),
        statusPages = listOf(EntryPageOption("dev", "Dev Status")),
        appServerUrl = "https://uptime.example.org",
        chromeTest = ChromeTest.Found("155.0"),
    )

@Preview(showBackground = true, heightDp = 1500)
@Composable
private fun GeneralSettingsPreview() {
    KumaTheme(dynamicColor = false) {
        GeneralSettingsContent(previewSettingsState, {}, {}, {}, {}, {}, {})
    }
}

@Preview(showBackground = true, heightDp = 1500, locale = "de", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun GeneralSettingsDarkGermanPreview() {
    KumaTheme(dynamicColor = false) {
        GeneralSettingsContent(previewSettingsState, {}, {}, {}, {}, {}, {})
    }
}
