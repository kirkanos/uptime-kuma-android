package net.kirkanos.uptimekuma.feature.statuspage.edit

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.kirkanos.uptimekuma.core.model.Monitor
import net.kirkanos.uptimekuma.core.model.StatusPageAnalytics
import net.kirkanos.uptimekuma.core.model.StatusPageConfig
import net.kirkanos.uptimekuma.core.model.StatusPageGroup
import net.kirkanos.uptimekuma.core.model.StatusPageMonitor
import net.kirkanos.uptimekuma.core.model.StatusPageTheme
import net.kirkanos.uptimekuma.core.ui.component.ErrorState
import net.kirkanos.uptimekuma.core.ui.component.LoadingState
import net.kirkanos.uptimekuma.core.ui.theme.KumaTheme
import net.kirkanos.uptimekuma.feature.statuspage.PreviewData
import net.kirkanos.uptimekuma.feature.statuspage.R
import net.kirkanos.uptimekuma.feature.statuspage.analyticsLabel
import net.kirkanos.uptimekuma.feature.statuspage.slugProblemText
import net.kirkanos.uptimekuma.feature.statuspage.statusPageErrorText
import net.kirkanos.uptimekuma.feature.statuspage.supportsLink
import net.kirkanos.uptimekuma.feature.statuspage.themeLabel

/** Callbacks of the editor (mirrors [StatusPageEditViewModel]). */
data class StatusPageEditActions(
    val onBack: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onSave: () -> Unit = {},
    val onDismissSaveError: () -> Unit = {},
    val onConfig: ((StatusPageConfig) -> StatusPageConfig) -> Unit = {},
    val onRefreshText: (String) -> Unit = {},
    val onAddDomain: () -> Unit = {},
    val onDomain: (Int, String) -> Unit = { _, _ -> },
    val onRemoveDomain: (Int) -> Unit = {},
    val onAddGroup: (String) -> Unit = {},
    val onRenameGroup: (Int, String) -> Unit = { _, _ -> },
    val onMoveGroup: (Int, Int) -> Unit = { _, _ -> },
    val onDeleteGroup: (Int) -> Unit = {},
    val onAddMonitors: (Int, List<Long>) -> Unit = { _, _ -> },
    val onRemoveMonitor: (Int, Int) -> Unit = { _, _ -> },
    val onMoveMonitor: (Int, Int, Int) -> Unit = { _, _, _ -> },
    val onSendUrl: (Int, Int, Boolean) -> Unit = { _, _, _ -> },
    val onCustomUrl: (Int, Int, String) -> Unit = { _, _, _ -> },
)

@Composable
internal fun StatusPageEditEntry(
    onBack: () -> Unit,
    onSaved: (String) -> Unit,
    viewModel: StatusPageEditViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.savedSlug) {
        state.savedSlug?.let(onSaved)
    }
    val actions =
        remember(viewModel, onBack) {
            StatusPageEditActions(
                onBack = onBack,
                onRetry = viewModel::load,
                onSave = viewModel::save,
                onDismissSaveError = viewModel::dismissSaveError,
                onConfig = viewModel::updateConfig,
                onRefreshText = viewModel::setRefreshText,
                onAddDomain = viewModel::addDomain,
                onDomain = viewModel::setDomain,
                onRemoveDomain = viewModel::removeDomain,
                onAddGroup = viewModel::addGroup,
                onRenameGroup = viewModel::renameGroup,
                onMoveGroup = viewModel::moveGroup,
                onDeleteGroup = viewModel::deleteGroup,
                onAddMonitors = viewModel::addMonitors,
                onRemoveMonitor = viewModel::removeMonitor,
                onMoveMonitor = viewModel::moveMonitor,
                onSendUrl = viewModel::setSendUrl,
                onCustomUrl = viewModel::setCustomUrl,
            )
        }
    StatusPageEditScreen(state, actions)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StatusPageEditScreen(
    state: StatusPageEditUiState,
    actions: StatusPageEditActions,
) {
    val snackbar = remember { SnackbarHostState() }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var pickerForGroup by rememberSaveable { mutableStateOf<Int?>(null) }
    val requestBack = { if (state.dirty) confirmDiscard = true else actions.onBack() }
    BackHandler(enabled = state.dirty) { confirmDiscard = true }

    state.saveError?.let { error ->
        val message = statusPageErrorText(error)
        LaunchedEffect(error) {
            snackbar.showSnackbar(message)
            actions.onDismissSaveError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.statuspage_edit_title)) },
                navigationIcon = {
                    IconButton(onClick = requestBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.statuspage_back),
                        )
                    }
                },
                actions = {
                    if (state.saving) {
                        CircularProgressIndicator(Modifier.padding(horizontal = 16.dp).heightIn(max = 24.dp))
                    } else {
                        TextButton(onClick = actions.onSave, enabled = state.form != null) {
                            Text(stringResource(R.string.statuspage_save))
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val form = state.form
        when {
            state.loading -> {
                LoadingState(Modifier.padding(padding))
            }

            form == null -> {
                state.loadError?.let { ErrorState(it, Modifier.padding(padding), onRetry = actions.onRetry) }
            }

            else -> {
                EditForm(form, state.showValidation, actions, Modifier.padding(padding), onPickMonitors = {
                    pickerForGroup =
                        it
                })
            }
        }
    }

    pickerForGroup?.let { groupIndex ->
        MonitorPickerDialog(
            monitors = state.availableMonitors,
            onAdd = { ids ->
                actions.onAddMonitors(groupIndex, ids)
                pickerForGroup = null
            },
            onDismiss = { pickerForGroup = null },
        )
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.statuspage_discard_title)) },
            text = { Text(stringResource(R.string.statuspage_discard_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDiscard = false
                        actions.onBack()
                    },
                ) { Text(stringResource(R.string.statuspage_discard_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.statuspage_cancel)) }
            },
        )
    }
}

@Composable
private fun EditForm(
    form: StatusPageForm,
    showValidation: Boolean,
    actions: StatusPageEditActions,
    modifier: Modifier,
    onPickMonitors: (Int) -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        generalSection(form, showValidation, actions)
        appearanceSection(form.config, actions)
        domainSection(form.config.domainNameList, actions)
        analyticsSection(form.config, actions)
        advancedSection(form.config, actions)
        groupSection(form.groups, actions, onPickMonitors)
    }
}

private fun LazyListScope.sectionTitle(
    key: String,
    title: @Composable () -> String,
) {
    item(key = key) {
        Text(
            title(),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 8.dp).semantics { heading() },
        )
    }
}

private fun LazyListScope.generalSection(
    form: StatusPageForm,
    showValidation: Boolean,
    actions: StatusPageEditActions,
) {
    val config = form.config
    sectionTitle("general") { stringResource(R.string.statuspage_section_general) }
    item(key = "title") {
        OutlinedTextField(
            value = config.title,
            onValueChange = { value -> actions.onConfig { it.copy(title = value) } },
            label = { Text(stringResource(R.string.statuspage_field_name)) },
            singleLine = true,
            isError = showValidation && form.titleMissing,
            supportingText = {
                if (showValidation && form.titleMissing) Text(stringResource(R.string.statuspage_name_missing))
            },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    item(key = "slug") {
        val problem = form.slugProblem
        OutlinedTextField(
            value = config.slug,
            onValueChange = { value -> actions.onConfig { it.copy(slug = value.lowercase().replace(' ', '-')) } },
            label = { Text(stringResource(R.string.statuspage_field_slug)) },
            prefix = { Text("/status/") },
            singleLine = true,
            isError = problem != null,
            supportingText = {
                Text(problem?.let { slugProblemText(it) } ?: stringResource(R.string.statuspage_slug_help))
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    item(key = "description") {
        OutlinedTextField(
            value = config.description.orEmpty(),
            onValueChange = { value -> actions.onConfig { it.copy(description = value) } },
            label = { Text(stringResource(R.string.statuspage_field_description)) },
            supportingText = { Text(stringResource(R.string.statuspage_markdown_hint)) },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    item(key = "footer") {
        OutlinedTextField(
            value = config.footerText.orEmpty(),
            onValueChange = { value -> actions.onConfig { it.copy(footerText = value) } },
            label = { Text(stringResource(R.string.statuspage_field_footer)) },
            supportingText = { Text(stringResource(R.string.statuspage_markdown_hint)) },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    item(key = "refresh") { RefreshField(form, actions) }
    item(key = "logo") {
        Text(
            stringResource(R.string.statuspage_logo_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RefreshField(
    form: StatusPageForm,
    actions: StatusPageEditActions,
) {
    val invalid = form.refreshSeconds == null
    OutlinedTextField(
        value = form.refreshText,
        onValueChange = actions.onRefreshText,
        label = { Text(stringResource(R.string.statuspage_field_refresh)) },
        singleLine = true,
        isError = invalid,
        supportingText = {
            Text(
                if (invalid) {
                    stringResource(R.string.statuspage_refresh_invalid)
                } else {
                    stringResource(R.string.statuspage_refresh_help, form.refreshText)
                },
            )
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
private fun LazyListScope.appearanceSection(
    config: StatusPageConfig,
    actions: StatusPageEditActions,
) {
    sectionTitle("appearance") { stringResource(R.string.statuspage_section_appearance) }
    item(key = "theme") {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.statuspage_field_theme), style = MaterialTheme.typography.labelLarge)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                StatusPageTheme.entries.forEachIndexed { index, theme ->
                    SegmentedButton(
                        selected = config.theme == theme,
                        onClick = { actions.onConfig { it.copy(theme = theme) } },
                        shape = SegmentedButtonDefaults.itemShape(index, StatusPageTheme.entries.size),
                    ) { Text(themeLabel(theme)) }
                }
            }
        }
    }
    item(key = "switches") {
        Column {
            SwitchRow(stringResource(R.string.statuspage_show_tags), config.showTags) { v ->
                actions.onConfig {
                    it.copy(showTags = v)
                }
            }
            SwitchRow(stringResource(R.string.statuspage_show_powered_by), config.showPoweredBy) { v ->
                actions.onConfig { it.copy(showPoweredBy = v) }
            }
            SwitchRow(stringResource(R.string.statuspage_show_cert_expiry), config.showCertificateExpiry) { v ->
                actions.onConfig { it.copy(showCertificateExpiry = v) }
            }
            SwitchRow(stringResource(R.string.statuspage_show_only_last_heartbeat), config.showOnlyLastHeartbeat) { v ->
                actions.onConfig { it.copy(showOnlyLastHeartbeat = v) }
            }
        }
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(label) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        modifier = Modifier.clickable { onChange(!checked) },
    )
}

private fun LazyListScope.domainSection(
    domains: List<String>,
    actions: StatusPageEditActions,
) {
    sectionTitle("domains") { stringResource(R.string.statuspage_section_domains) }
    itemsIndexed(domains, key = { index, _ -> "domain-$index" }) { index, domain ->
        OutlinedTextField(
            value = domain,
            onValueChange = { actions.onDomain(index, it) },
            placeholder = { Text(stringResource(R.string.statuspage_domain_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            trailingIcon = {
                IconButton(onClick = { actions.onRemoveDomain(index) }) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.statuspage_remove_domain, domain),
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    item(key = "add-domain") {
        OutlinedButton(onClick = actions.onAddDomain) {
            Icon(Icons.Outlined.Add, contentDescription = null)
            Text(stringResource(R.string.statuspage_add_domain), Modifier.padding(start = 8.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
private fun LazyListScope.analyticsSection(
    config: StatusPageConfig,
    actions: StatusPageEditActions,
) {
    sectionTitle("analytics") { stringResource(R.string.statuspage_section_analytics) }
    item(key = "analytics-type") {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.statuspage_field_analytics_type), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = config.analyticsType == null,
                    onClick = { actions.onConfig { it.copy(analyticsType = null) } },
                    label = { Text(stringResource(R.string.statuspage_analytics_none)) },
                )
                StatusPageAnalytics.entries.forEach { type ->
                    FilterChip(
                        selected = config.analyticsType == type,
                        onClick = { actions.onConfig { it.copy(analyticsType = type) } },
                        label = { Text(analyticsLabel(type)) },
                    )
                }
            }
        }
    }
    val type = config.analyticsType ?: return
    item(key = "analytics-id") {
        OutlinedTextField(
            value = config.analyticsId.orEmpty(),
            onValueChange = { value -> actions.onConfig { it.copy(analyticsId = value) } },
            label = { Text(stringResource(R.string.statuspage_field_analytics_id)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (type.usesScriptUrl) {
        item(key = "analytics-script") {
            OutlinedTextField(
                value = config.analyticsScriptUrl.orEmpty(),
                onValueChange = { value -> actions.onConfig { it.copy(analyticsScriptUrl = value) } },
                label = { Text(stringResource(R.string.statuspage_field_analytics_script)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun LazyListScope.advancedSection(
    config: StatusPageConfig,
    actions: StatusPageEditActions,
) {
    sectionTitle("advanced") { stringResource(R.string.statuspage_section_advanced) }
    item(key = "rss") {
        OutlinedTextField(
            value = config.rssTitle.orEmpty(),
            onValueChange = { value -> actions.onConfig { it.copy(rssTitle = value) } },
            label = { Text(stringResource(R.string.statuspage_field_rss_title)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    item(key = "css") {
        OutlinedTextField(
            value = config.customCss.orEmpty(),
            onValueChange = { value -> actions.onConfig { it.copy(customCss = value) } },
            label = { Text(stringResource(R.string.statuspage_field_custom_css)) },
            minLines = 3,
            textStyle =
                MaterialTheme.typography.bodySmall.copy(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun LazyListScope.groupSection(
    groups: List<StatusPageGroup>,
    actions: StatusPageEditActions,
    onPickMonitors: (Int) -> Unit,
) {
    sectionTitle("groups") { stringResource(R.string.statuspage_section_groups) }
    item(key = "add-group") {
        val name =
            stringResource(
                if (groups.isEmpty()) R.string.statuspage_group_services else R.string.statuspage_group_untitled,
            )
        FilledTonalButton(onClick = { actions.onAddGroup(name) }) {
            Icon(Icons.Outlined.Add, contentDescription = null)
            Text(stringResource(R.string.statuspage_add_group), Modifier.padding(start = 8.dp))
        }
    }
    if (groups.isEmpty()) {
        item(key = "no-groups") {
            Text(stringResource(R.string.statuspage_no_groups), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    // Keys by position: groups without id are new, and names can repeat.
    itemsIndexed(groups, key = { index, _ -> "group-$index" }) { index, group ->
        GroupEditor(
            group = group,
            index = index,
            count = groups.size,
            actions = actions,
            onPickMonitors = { onPickMonitors(index) },
        )
    }
}

@Composable
private fun GroupEditor(
    group: StatusPageGroup,
    index: Int,
    count: Int,
    actions: StatusPageEditActions,
    onPickMonitors: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = group.name,
                    onValueChange = { actions.onRenameGroup(index, it) },
                    label = { Text(stringResource(R.string.statuspage_group_name)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { actions.onMoveGroup(index, -1) }, enabled = index > 0) {
                    Icon(Icons.Outlined.KeyboardArrowUp, stringResource(R.string.statuspage_move_group_up, group.name))
                }
                IconButton(onClick = { actions.onMoveGroup(index, 1) }, enabled = index < count - 1) {
                    Icon(
                        Icons.Outlined.KeyboardArrowDown,
                        stringResource(R.string.statuspage_move_group_down, group.name),
                    )
                }
                IconButton(onClick = { actions.onDeleteGroup(index) }) {
                    Icon(
                        Icons.Outlined.Delete,
                        stringResource(R.string.statuspage_delete_group, group.name),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (group.monitors.isEmpty()) {
                Text(
                    stringResource(R.string.statuspage_group_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            group.monitors.forEachIndexed { monitorIndex, monitor ->
                if (monitorIndex > 0) HorizontalDivider()
                MonitorEditor(monitor, monitorIndex, group.monitors.size, index, actions)
            }
            TextButton(onClick = onPickMonitors) {
                Icon(Icons.Outlined.Add, contentDescription = null)
                Text(stringResource(R.string.statuspage_add_monitors), Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun MonitorEditor(
    monitor: StatusPageMonitor,
    index: Int,
    count: Int,
    groupIndex: Int,
    actions: StatusPageEditActions,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(monitor.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = { actions.onMoveMonitor(groupIndex, index, -1) }, enabled = index > 0) {
                Icon(Icons.Outlined.KeyboardArrowUp, stringResource(R.string.statuspage_move_monitor_up, monitor.name))
            }
            IconButton(onClick = { actions.onMoveMonitor(groupIndex, index, 1) }, enabled = index < count - 1) {
                Icon(
                    Icons.Outlined.KeyboardArrowDown,
                    stringResource(R.string.statuspage_move_monitor_down, monitor.name),
                )
            }
            IconButton(onClick = { actions.onRemoveMonitor(groupIndex, index) }) {
                Icon(Icons.Outlined.Close, stringResource(R.string.statuspage_remove_monitor, monitor.name))
            }
        }
        if (supportsLink(monitor.type) || monitor.url != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { actions.onSendUrl(groupIndex, index, !monitor.sendUrl) },
            ) {
                Text(
                    stringResource(R.string.statuspage_show_link),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = monitor.sendUrl, onCheckedChange = null)
            }
            if (monitor.sendUrl) {
                OutlinedTextField(
                    value = monitor.url.orEmpty(),
                    onValueChange = { actions.onCustomUrl(groupIndex, index, it) },
                    label = { Text(stringResource(R.string.statuspage_custom_url)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** Searchable multi-select list of monitors that are not on the page yet. */
@Composable
internal fun MonitorPickerDialog(
    monitors: List<Monitor>,
    onAdd: (List<Long>) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val selected = remember { mutableStateListOf<Long>() }
    val filtered =
        remember(monitors, query) {
            monitors.filter { query.isBlank() || it.pathName.contains(query.trim(), ignoreCase = true) }
        }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.statuspage_add_monitors)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.statuspage_picker_search)) },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (filtered.isEmpty()) {
                    Text(
                        stringResource(R.string.statuspage_picker_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(filtered, key = { it.id }) { monitor ->
                        val checked = monitor.id in selected
                        ListItem(
                            headlineContent = { Text(monitor.pathName) },
                            leadingContent = { Checkbox(checked = checked, onCheckedChange = null) },
                            modifier =
                                Modifier.clickable {
                                    if (checked) selected.remove(monitor.id) else selected.add(monitor.id)
                                },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(selected.toList()) }, enabled = selected.isNotEmpty()) {
                Text(stringResource(R.string.statuspage_picker_add, selected.size))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.statuspage_cancel)) } },
    )
}

@Preview(showBackground = true, heightDp = 2400)
@Composable
private fun StatusPageEditPreview() {
    KumaTheme(dynamicColor = false) {
        StatusPageEditScreen(
            StatusPageEditUiState(
                originalSlug = "status",
                loading = false,
                form =
                    StatusPageForm(
                        PreviewData.config.copy(analyticsType = StatusPageAnalytics.UMAMI),
                        groups = PreviewData.groups,
                    ),
                monitors = PreviewData.monitors,
            ),
            StatusPageEditActions(),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun MonitorPickerPreview() {
    KumaTheme(dynamicColor = false) {
        MonitorPickerDialog(PreviewData.monitors, onAdd = {}, onDismiss = {})
    }
}
