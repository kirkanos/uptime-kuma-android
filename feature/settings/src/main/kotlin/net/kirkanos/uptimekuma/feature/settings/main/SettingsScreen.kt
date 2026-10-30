package net.kirkanos.uptimekuma.feature.settings.main

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.kirkanos.uptimekuma.core.domain.PushSetupState
import net.kirkanos.uptimekuma.core.model.AlertPreferences
import net.kirkanos.uptimekuma.core.model.QuietHours
import net.kirkanos.uptimekuma.core.model.RepeatInterval
import net.kirkanos.uptimekuma.core.model.ServerConfig
import net.kirkanos.uptimekuma.core.model.ServerInfo
import net.kirkanos.uptimekuma.core.ui.format.currentLocale
import net.kirkanos.uptimekuma.core.ui.theme.KumaTheme
import net.kirkanos.uptimekuma.feature.settings.AdministrationEntry
import net.kirkanos.uptimekuma.feature.settings.R
import net.kirkanos.uptimekuma.feature.settings.server.ServerSection
import net.kirkanos.uptimekuma.push.ui.PushStatusCard
import net.kirkanos.uptimekuma.push.ui.PushStatusCardContent

private const val REPOSITORY_URL = "https://github.com/kirkanos/uptime-kuma-android"
private const val LICENSE_URL = "https://www.gnu.org/licenses/gpl-3.0.html"

@Composable
internal fun SettingsEntry(
    onBack: () -> Unit,
    onPushSetup: () -> Unit,
    onAlertHistory: () -> Unit,
    onServerSection: (ServerSection) -> Unit,
    administrationEntries: List<AdministrationEntry>,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    SettingsContent(
        state = state,
        appVersion = remember { appVersion(context) },
        administrationEntries = administrationEntries,
        actions =
            SettingsActions(
                onBack = onBack,
                onAlertHistory = onAlertHistory,
                onServerSection = onServerSection,
                onNotifyOnUp = viewModel::setNotifyOnUp,
                onNotifyOnCertExpiry = viewModel::setNotifyOnCertExpiry,
                onQuietHoursEnabled = viewModel::setQuietHoursEnabled,
                onQuietHoursStart = viewModel::setQuietHoursStart,
                onQuietHoursEnd = viewModel::setQuietHoursEnd,
                onRepeatDown = viewModel::setRepeatDown,
                onSystemNotificationSettings = { openNotificationSettings(context) },
                onLogout = viewModel::logout,
            ),
        pushStatus = { PushStatusCard(onManageClick = onPushSetup, modifier = Modifier.padding(horizontal = 16.dp)) },
    )
}

/** Callbacks of [SettingsContent], bundled to keep the signature short. */
internal data class SettingsActions(
    val onBack: () -> Unit = {},
    val onAlertHistory: () -> Unit = {},
    val onServerSection: (ServerSection) -> Unit = {},
    val onNotifyOnUp: (Boolean) -> Unit = {},
    val onNotifyOnCertExpiry: (Boolean) -> Unit = {},
    val onQuietHoursEnabled: (Boolean) -> Unit = {},
    val onQuietHoursStart: (Int) -> Unit = {},
    val onQuietHoursEnd: (Int) -> Unit = {},
    val onRepeatDown: (RepeatInterval) -> Unit = {},
    val onSystemNotificationSettings: () -> Unit = {},
    val onLogout: () -> Unit = {},
)

private enum class QuietHoursEdit { START, END }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsContent(
    state: SettingsUiState,
    appVersion: String?,
    administrationEntries: List<AdministrationEntry>,
    actions: SettingsActions,
    pushStatus: @Composable () -> Unit,
) {
    var confirmLogout by rememberSaveable { mutableStateOf(false) }
    var quietEdit by rememberSaveable { mutableStateOf<QuietHoursEdit?>(null) }
    var repeatEdit by rememberSaveable { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    val prefs = state.alertPreferences
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.settings_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            SectionHeader(stringResource(R.string.settings_section_notifications))
            pushStatus()
            SwitchRow(
                title = stringResource(R.string.settings_notify_up),
                summary = stringResource(R.string.settings_notify_up_summary),
                icon = Icons.Outlined.TaskAlt,
                checked = prefs.notifyOnUp,
                onCheckedChange = actions.onNotifyOnUp,
            )
            SwitchRow(
                title = stringResource(R.string.settings_notify_cert),
                summary = stringResource(R.string.settings_notify_cert_summary),
                icon = Icons.Outlined.VerifiedUser,
                checked = prefs.notifyOnCertExpiry,
                onCheckedChange = actions.onNotifyOnCertExpiry,
            )
            ClickRow(
                title = stringResource(R.string.settings_repeat_down),
                summary =
                    prefs.repeatDown.minutes?.let { stringResource(R.string.settings_repeat_down_summary_on, it) }
                        ?: stringResource(R.string.settings_repeat_down_summary_off),
                icon = Icons.Outlined.Repeat,
                onClick = { repeatEdit = true },
            )
            QuietHoursRows(
                quietHours = prefs.quietHours,
                onEnabledChange = actions.onQuietHoursEnabled,
                onEditStart = { quietEdit = QuietHoursEdit.START },
                onEditEnd = { quietEdit = QuietHoursEdit.END },
            )
            ClickRow(
                title = stringResource(R.string.settings_system_notifications),
                summary = stringResource(R.string.settings_system_notifications_summary),
                icon = Icons.Outlined.Settings,
                onClick = actions.onSystemNotificationSettings,
            )
            ClickRow(
                title = stringResource(R.string.settings_alert_history),
                summary = stringResource(R.string.settings_alert_history_summary),
                icon = Icons.Outlined.History,
                onClick = actions.onAlertHistory,
            )

            HorizontalDivider(Modifier.padding(top = 8.dp))
            SectionHeader(stringResource(R.string.settings_section_server))
            ServerRows(state.serverConfig, state.serverInfo)
            if (state.serverConfig != null) {
                ServerSection.entries.forEach { section ->
                    ClickRow(
                        title = stringResource(section.titleRes),
                        summary = stringResource(section.summaryRes),
                        icon = section.icon,
                        onClick = { actions.onServerSection(section) },
                    )
                }
            }

            if (administrationEntries.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(top = 8.dp))
                SectionHeader(stringResource(R.string.settings_section_administration))
                administrationEntries.forEach { entry ->
                    ClickRow(
                        title = stringResource(entry.titleRes),
                        summary = entry.summaryRes?.let { stringResource(it) },
                        icon = entry.icon,
                        onClick = entry.onClick,
                    )
                }
            }

            HorizontalDivider(Modifier.padding(top = 8.dp))
            SectionHeader(stringResource(R.string.settings_section_about))
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_version)) },
                supportingContent = { Text(appVersion ?: "–") },
                leadingContent = { Icon(Icons.Outlined.Info, contentDescription = null) },
            )
            ClickRow(
                title = stringResource(R.string.settings_license),
                summary = stringResource(R.string.settings_license_summary),
                icon = Icons.Outlined.Gavel,
                onClick = { uriHandler.openUri(LICENSE_URL) },
            )
            ClickRow(
                title = stringResource(R.string.settings_source),
                summary = REPOSITORY_URL.removePrefix("https://"),
                icon = Icons.Outlined.Code,
                onClick = { uriHandler.openUri(REPOSITORY_URL) },
            )

            HorizontalDivider(Modifier.padding(top = 8.dp))
            ListItem(
                headlineContent = {
                    Text(stringResource(R.string.settings_logout), color = MaterialTheme.colorScheme.error)
                },
                leadingContent = {
                    Icon(
                        Icons.AutoMirrored.Outlined.Logout,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                },
                modifier = Modifier.clickable { confirmLogout = true },
            )
        }
    }
    quietEdit?.let { edit ->
        val initial =
            if (edit == QuietHoursEdit.START) prefs.quietHours.startMinuteOfDay else prefs.quietHours.endMinuteOfDay
        TimePickerDialog(
            title =
                stringResource(
                    if (edit == QuietHoursEdit.START) R.string.settings_quiet_start else R.string.settings_quiet_end,
                ),
            initialMinuteOfDay = initial,
            onDismiss = { quietEdit = null },
            onConfirm = { minute ->
                quietEdit = null
                if (edit == QuietHoursEdit.START) actions.onQuietHoursStart(minute) else actions.onQuietHoursEnd(minute)
            },
        )
    }
    if (repeatEdit) {
        RepeatDownDialog(
            selected = prefs.repeatDown,
            onDismiss = { repeatEdit = false },
            onSelect = {
                repeatEdit = false
                actions.onRepeatDown(it)
            },
        )
    }
    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text(stringResource(R.string.settings_logout_title)) },
            text = { Text(stringResource(R.string.settings_logout_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmLogout = false
                        actions.onLogout()
                    },
                ) { Text(stringResource(R.string.settings_logout), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmLogout = false }) { Text(stringResource(R.string.settings_cancel)) }
            },
        )
    }
}

@Composable
private fun RepeatDownDialog(
    selected: RepeatInterval,
    onDismiss: () -> Unit,
    onSelect: (RepeatInterval) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_repeat_down)) },
        text = {
            Column(Modifier.selectableGroup()) {
                Text(
                    stringResource(R.string.settings_repeat_down_info),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                RepeatInterval.entries.forEach { interval ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = interval == selected,
                                    role = Role.RadioButton,
                                    onClick = { onSelect(interval) },
                                ).padding(vertical = 4.dp),
                    ) {
                        RadioButton(selected = interval == selected, onClick = null)
                        Text(
                            interval.minutes?.let { stringResource(R.string.settings_repeat_down_every, it) }
                                ?: stringResource(R.string.settings_repeat_down_off),
                            modifier = Modifier.padding(start = 16.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) }
        },
    )
}

@Composable
private fun QuietHoursRows(
    quietHours: QuietHours,
    onEnabledChange: (Boolean) -> Unit,
    onEditStart: () -> Unit,
    onEditEnd: () -> Unit,
) {
    val locale = currentLocale()
    val start = formatMinuteOfDay(quietHours.startMinuteOfDay, locale)
    val end = formatMinuteOfDay(quietHours.endMinuteOfDay, locale)
    SwitchRow(
        title = stringResource(R.string.settings_quiet_hours),
        summary =
            if (quietHours.enabled) {
                stringResource(R.string.settings_quiet_hours_summary_on, start, end)
            } else {
                stringResource(R.string.settings_quiet_hours_summary_off)
            },
        icon = Icons.Outlined.Bedtime,
        checked = quietHours.enabled,
        onCheckedChange = onEnabledChange,
    )
    if (quietHours.enabled) {
        ClickRow(
            title = stringResource(R.string.settings_quiet_start),
            summary = start,
            icon = null,
            onClick = onEditStart,
        )
        ClickRow(
            title = stringResource(R.string.settings_quiet_end),
            summary = end,
            icon = null,
            onClick = onEditEnd,
        )
    }
}

@Composable
private fun ServerRows(
    config: ServerConfig?,
    info: ServerInfo?,
) {
    if (config == null) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_server_none)) },
            leadingContent = { Icon(Icons.Outlined.Dns, contentDescription = null) },
        )
        return
    }
    ListItem(
        overlineContent = { Text(stringResource(R.string.settings_server)) },
        headlineContent = { Text(config.baseUrl) },
        supportingContent = {
            val account = stringResource(R.string.settings_logged_in_as, config.username)
            val version = info?.version?.let { stringResource(R.string.settings_server_version, it) }
            Text(listOfNotNull(account, version).joinToString("\n"))
        },
        leadingContent = { Icon(Icons.Outlined.Dns, contentDescription = null) },
    )
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp).semantics { heading() },
    )
}

@Composable
private fun SwitchRow(
    title: String,
    summary: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        modifier = Modifier.clickable(role = Role.Switch) { onCheckedChange(!checked) },
    )
}

@Composable
private fun ClickRow(
    title: String,
    summary: String?,
    icon: ImageVector?,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = summary?.let { { Text(it) } },
        leadingContent = icon?.let { { Icon(it, contentDescription = null) } },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

private fun appVersion(context: Context): String? =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()

private fun openNotificationSettings(context: Context) {
    val intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // Some ROMs lack the screen; nothing sensible to fall back to.
    }
}

@Preview(showBackground = true, heightDp = 1400)
@Composable
private fun SettingsPreview() {
    KumaTheme(dynamicColor = false) {
        SettingsContent(
            state =
                SettingsUiState(
                    loading = false,
                    serverConfig = ServerConfig(baseUrl = "https://uptime.example.org", username = "admin"),
                    serverInfo = ServerInfo(version = "2.5.8"),
                    alertPreferences = AlertPreferences(quietHours = QuietHours(enabled = true)),
                ),
            appVersion = "0.0.1",
            administrationEntries =
                listOf(
                    AdministrationEntry(
                        titleRes = R.string.settings_section_administration,
                        icon = Icons.Outlined.NotificationsActive,
                        onClick = {},
                    ),
                ),
            actions = SettingsActions(),
            pushStatus = {
                PushStatusCardContent(
                    state = PushSetupState.Active,
                    onManageClick = {},
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            },
        )
    }
}

@Preview(showBackground = true, heightDp = 1200, locale = "de", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SettingsDarkGermanPreview() {
    KumaTheme(dynamicColor = false) {
        SettingsContent(
            state = SettingsUiState(loading = false),
            appVersion = "0.0.1",
            administrationEntries = emptyList(),
            actions = SettingsActions(),
            pushStatus = {
                PushStatusCardContent(
                    state = PushSetupState.NotConfigured,
                    onManageClick = {},
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            },
        )
    }
}
