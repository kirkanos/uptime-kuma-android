package net.kirkanos.uptimekuma.feature.maintenance.edit

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.kirkanos.uptimekuma.core.model.Maintenance
import net.kirkanos.uptimekuma.core.model.MaintenanceDraft
import net.kirkanos.uptimekuma.core.model.MaintenanceMonthDay
import net.kirkanos.uptimekuma.core.model.MaintenanceStrategy
import net.kirkanos.uptimekuma.core.ui.component.ErrorState
import net.kirkanos.uptimekuma.core.ui.component.LoadingState
import net.kirkanos.uptimekuma.core.ui.component.localizedMessage
import net.kirkanos.uptimekuma.core.ui.format.currentLocale
import net.kirkanos.uptimekuma.core.ui.theme.KumaTheme
import net.kirkanos.uptimekuma.feature.maintenance.PreviewData
import net.kirkanos.uptimekuma.feature.maintenance.R
import net.kirkanos.uptimekuma.feature.maintenance.WEEKDAY_ORDER
import net.kirkanos.uptimekuma.feature.maintenance.cronDescription
import net.kirkanos.uptimekuma.feature.maintenance.formatTimeOfDay
import net.kirkanos.uptimekuma.feature.maintenance.formatWallClock
import net.kirkanos.uptimekuma.feature.maintenance.labelRes
import net.kirkanos.uptimekuma.feature.maintenance.weekdayName
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.TextStyle

private val QUICK_DURATIONS = listOf(15L, 30L, 60L, 120L, 240L, 480L, 720L, 1440L)
private const val MAX_DAY = 31
private const val MINUTES_PER_HOUR = 60L

@Immutable
internal class MaintenanceEditActions(
    val onBack: () -> Unit = {},
    val onEdit: (DraftChange) -> Unit = {},
    val onSave: (Boolean) -> Unit = {},
    val onDismissConfirm: () -> Unit = {},
    val onDismissError: () -> Unit = {},
    val onRetryLoad: () -> Unit = {},
    val onCloneTitle: (String) -> Unit = {},
)

@Composable
internal fun MaintenanceEditEntry(
    onDone: () -> Unit,
    viewModel: MaintenanceEditViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.done) { if (state.done) onDone() }
    val actions =
        remember(viewModel, onDone) {
            MaintenanceEditActions(
                onBack = onDone,
                onEdit = viewModel::edit,
                onSave = viewModel::save,
                onDismissConfirm = viewModel::dismissConfirm,
                onDismissError = viewModel::dismissSaveError,
                onRetryLoad = viewModel::load,
                onCloneTitle = viewModel::applyCloneTitle,
            )
        }
    MaintenanceEditScreen(state = state, actions = actions)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MaintenanceEditScreen(
    state: MaintenanceEditUiState,
    actions: MaintenanceEditActions,
    modifier: Modifier = Modifier,
) {
    state.pendingCloneTitle?.let { source ->
        val title = stringResource(R.string.maintenance_clone_of, source)
        LaunchedEffect(source) { actions.onCloneTitle(title) }
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val saveError =
        state.saveError?.let { error ->
            if (state.linkFailed) {
                stringResource(R.string.maintenance_link_error, error.localizedMessage())
            } else {
                error.localizedMessage()
            }
        }
    LaunchedEffect(saveError) {
        if (saveError != null) {
            snackbarHostState.showSnackbar(saveError)
            actions.onDismissError()
        }
    }
    val titleRes =
        when {
            state.isClone -> R.string.maintenance_edit_clone
            state.isNew -> R.string.maintenance_edit_new
            else -> R.string.maintenance_edit_existing
        }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(titleRes)) },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(Icons.Outlined.Close, stringResource(R.string.maintenance_cancel))
                    }
                },
                actions = {
                    if (state.saving) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(horizontal = 16.dp).size(24.dp),
                            strokeWidth = 2.dp,
                        )
                    } else if (!state.loading && state.loadError == null) {
                        TextButton(
                            onClick = { actions.onSave(false) },
                        ) { Text(stringResource(R.string.maintenance_save)) }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        val contentModifier = Modifier.padding(padding).fillMaxSize()
        when {
            state.loading -> LoadingState(contentModifier)
            state.loadError != null -> ErrorState(state.loadError, contentModifier, actions.onRetryLoad)
            else -> EditorForm(state, actions, contentModifier)
        }
    }
    if (state.confirmNoMonitors) {
        AlertDialog(
            onDismissRequest = actions.onDismissConfirm,
            title = { Text(stringResource(R.string.maintenance_confirm_no_monitors_title)) },
            text = { Text(stringResource(R.string.maintenance_confirm_no_monitors_message)) },
            confirmButton = {
                TextButton(
                    onClick = { actions.onSave(true) },
                ) { Text(stringResource(R.string.maintenance_save_anyway)) }
            },
            dismissButton = {
                TextButton(onClick = actions.onDismissConfirm) { Text(stringResource(R.string.maintenance_cancel)) }
            },
        )
    }
}

@Composable
private fun EditorForm(
    state: MaintenanceEditUiState,
    actions: MaintenanceEditActions,
    modifier: Modifier,
) {
    val errors = state.visibleErrors
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (errors.isNotEmpty()) {
            Text(
                stringResource(R.string.maintenance_error_fix_fields),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        BasicsSection(state.draft, errors, actions.onEdit)
        MonitorsSection(state, errors, actions.onEdit)
        StatusPagesSection(state, actions.onEdit)
        ScheduleSection(state, errors, actions.onEdit)
    }
}

@Composable
private fun SectionTitle(
    @StringRes text: Int,
) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp).semantics { heading() },
    )
}

@Composable
private fun ErrorLine(error: MaintenanceFieldError?) {
    if (error != null) {
        Text(
            stringResource(error.messageRes()),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun BasicsSection(
    draft: MaintenanceDraft,
    errors: Set<MaintenanceFieldError>,
    onEdit: (DraftChange) -> Unit,
) {
    val titleError =
        errors.firstOrNull { it == MaintenanceFieldError.TITLE_REQUIRED || it == MaintenanceFieldError.TITLE_TOO_LONG }
    OutlinedTextField(
        value = draft.title,
        onValueChange = { onEdit(DraftChange.Title(it)) },
        label = { Text(stringResource(R.string.maintenance_field_title)) },
        isError = titleError != null,
        supportingText = {
            Text(
                titleError?.let { stringResource(it.messageRes()) }
                    ?: "${draft.title.length} / ${Maintenance.TITLE_MAX_LENGTH}",
            )
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = draft.description,
        onValueChange = { onEdit(DraftChange.Description(it)) },
        label = { Text(stringResource(R.string.maintenance_field_description)) },
        supportingText = { Text(stringResource(R.string.maintenance_description_hint)) },
        minLines = 3,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun MonitorsSection(
    state: MaintenanceEditUiState,
    errors: Set<MaintenanceFieldError>,
    onEdit: (DraftChange) -> Unit,
) {
    var picking by rememberSaveable { mutableStateOf(false) }
    val selected = state.draft.monitorIds
    SectionTitle(R.string.maintenance_section_monitors)
    Text(stringResource(R.string.maintenance_monitors_hint), style = MaterialTheme.typography.bodyMedium)
    val names = state.monitors.associate { it.id to it.name }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        selected.sortedBy { names[it] ?: "" }.forEach { id ->
            val name = names[id] ?: stringResource(R.string.maintenance_monitor_unknown, id)
            InputChip(
                selected = true,
                onClick = { onEdit(DraftChange.Monitors(selected - id)) },
                label = { Text(name) },
                trailingIcon = {
                    Icon(
                        Icons.Outlined.Clear,
                        contentDescription = stringResource(R.string.maintenance_remove_monitor, name),
                        modifier = Modifier.size(18.dp),
                    )
                },
            )
        }
    }
    OutlinedButton(onClick = { picking = true }) {
        Text(
            if (selected.isEmpty()) {
                stringResource(R.string.maintenance_pick_monitors)
            } else {
                pluralStringResource(R.plurals.maintenance_monitors_selected, selected.size, selected.size)
            },
        )
    }
    ErrorLine(errors.firstOrNull { it == MaintenanceFieldError.TARGETS_REQUIRED })
    if (picking) {
        MonitorPickerDialog(
            options = state.monitors,
            selected = selected,
            onDismiss = { picking = false },
            onConfirm = {
                picking = false
                onEdit(DraftChange.Monitors(it))
            },
        )
    }
}

@Composable
private fun StatusPagesSection(
    state: MaintenanceEditUiState,
    onEdit: (DraftChange) -> Unit,
) {
    SectionTitle(R.string.maintenance_section_status_pages)
    Text(stringResource(R.string.maintenance_status_pages_hint), style = MaterialTheme.typography.bodyMedium)
    if (state.statusPages.isEmpty()) {
        Text(
            stringResource(R.string.maintenance_no_status_pages_available),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }
    LabeledToggle(
        label = stringResource(R.string.maintenance_all_status_pages),
        checked = state.allStatusPages,
        onChange = { onEdit(DraftChange.AllStatusPages(it)) },
        switch = true,
    )
    if (!state.allStatusPages) {
        state.statusPages.forEach { page ->
            LabeledToggle(
                label = page.title,
                checked = page.id in state.draft.statusPageIds,
                onChange = { onEdit(DraftChange.ToggleStatusPage(page.id)) },
            )
        }
    }
}

@Composable
private fun LabeledToggle(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    switch: Boolean = false,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .toggleable(
                    value = checked,
                    role = if (switch) Role.Switch else Role.Checkbox,
                    onValueChange = onChange,
                ).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (switch) {
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Switch(checked = checked, onCheckedChange = null)
        } else {
            Checkbox(checked = checked, onCheckedChange = null)
            Text(label, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun ScheduleSection(
    state: MaintenanceEditUiState,
    errors: Set<MaintenanceFieldError>,
    onEdit: (DraftChange) -> Unit,
) {
    val draft = state.draft
    SectionTitle(R.string.maintenance_section_date_time)
    Text(stringResource(R.string.maintenance_field_strategy), style = MaterialTheme.typography.labelLarge)
    Column(Modifier.selectableGroup()) {
        MaintenanceStrategy.entries.forEach { strategy ->
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .selectable(selected = draft.strategy == strategy, role = Role.RadioButton) {
                            onEdit(DraftChange.Strategy(strategy))
                        }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RadioButton(selected = draft.strategy == strategy, onClick = null)
                Text(stringResource(strategy.labelRes()), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
    when (draft.strategy) {
        MaintenanceStrategy.MANUAL -> {
            Text(stringResource(R.string.maintenance_manual_hint), style = MaterialTheme.typography.bodyMedium)
        }

        MaintenanceStrategy.CRON -> {
            CronFields(state, errors, onEdit)
        }

        MaintenanceStrategy.RECURRING_INTERVAL -> {
            IntervalField(state, errors, onEdit)
        }

        MaintenanceStrategy.RECURRING_WEEKDAY -> {
            WeekdayChips(draft, errors, onEdit)
        }

        MaintenanceStrategy.RECURRING_DAY_OF_MONTH -> {
            MonthDayChips(draft, errors, onEdit)
        }

        MaintenanceStrategy.SINGLE -> {}
    }
    if (draft.strategy.isRecurring) TimeWindow(draft, onEdit)
    if (draft.strategy != MaintenanceStrategy.MANUAL) {
        TimezoneField(draft.timezoneOption, state.serverTimezone, onEdit)
        DateRange(draft, errors, onEdit)
    }
    if (draft.strategy == MaintenanceStrategy.SINGLE) QuickDurations(draft, onEdit)
}

@Composable
private fun CronFields(
    state: MaintenanceEditUiState,
    errors: Set<MaintenanceFieldError>,
    onEdit: (DraftChange) -> Unit,
) {
    val cronError = MaintenanceFieldError.CRON_INVALID in errors
    val description = cronDescription(state.draft.cron)
    OutlinedTextField(
        value = state.draft.cron,
        onValueChange = { onEdit(DraftChange.Cron(it)) },
        label = { Text(stringResource(R.string.maintenance_field_cron)) },
        isError = cronError,
        supportingText = {
            Text(
                when {
                    cronError -> stringResource(R.string.maintenance_error_cron)
                    description != null -> description
                    else -> stringResource(R.string.maintenance_cron_hint)
                },
            )
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    NumberField(
        value = state.durationText,
        label = R.string.maintenance_field_duration,
        error = errors.firstOrNull { it == MaintenanceFieldError.DURATION_INVALID },
        onChange = { onEdit(DraftChange.Duration(it)) },
    )
}

@Composable
private fun IntervalField(
    state: MaintenanceEditUiState,
    errors: Set<MaintenanceFieldError>,
    onEdit: (DraftChange) -> Unit,
) {
    val days = state.draft.intervalDay
    NumberField(
        value = state.intervalText,
        label = R.string.maintenance_field_interval,
        error = errors.firstOrNull { it == MaintenanceFieldError.INTERVAL_INVALID },
        hint =
            if (days >= 1) {
                pluralStringResource(
                    R.plurals.maintenance_schedule_interval,
                    days,
                    days,
                    formatTimeOfDay(state.draft.startTime, currentLocale()),
                    formatTimeOfDay(state.draft.endTime, currentLocale()),
                )
            } else {
                null
            },
        onChange = { onEdit(DraftChange.Interval(it)) },
    )
}

@Composable
private fun NumberField(
    value: String,
    @StringRes label: Int,
    error: MaintenanceFieldError?,
    onChange: (String) -> Unit,
    hint: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { text -> onChange(text.filter(Char::isDigit)) },
        label = { Text(stringResource(label)) },
        isError = error != null,
        supportingText = (error?.let { stringResource(it.messageRes()) } ?: hint)?.let { text -> { Text(text) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun WeekdayChips(
    draft: MaintenanceDraft,
    errors: Set<MaintenanceFieldError>,
    onEdit: (DraftChange) -> Unit,
) {
    val locale = currentLocale()
    Text(stringResource(R.string.maintenance_field_weekdays), style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        WEEKDAY_ORDER.forEach { day ->
            val name = weekdayName(day, locale)
            FilterChip(
                selected = day in draft.weekdays,
                onClick = { onEdit(DraftChange.ToggleWeekday(day)) },
                label = { Text(name) },
                modifier = Modifier.semantics { contentDescription = weekdayName(day, locale, TextStyle.FULL) },
            )
        }
    }
    ErrorLine(errors.firstOrNull { it == MaintenanceFieldError.WEEKDAYS_REQUIRED })
}

@Composable
private fun MonthDayChips(
    draft: MaintenanceDraft,
    errors: Set<MaintenanceFieldError>,
    onEdit: (DraftChange) -> Unit,
) {
    Text(stringResource(R.string.maintenance_field_days), style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (day in 1..MAX_DAY) {
            val value = MaintenanceMonthDay.Day(day)
            FilterChip(
                selected = value in draft.daysOfMonth,
                onClick = { onEdit(DraftChange.ToggleMonthDay(value)) },
                label = { Text(day.toString()) },
            )
        }
    }
    // Kuma 2.5 offers (and schedules) only lastDay1; lastDay2-4 from older data stay selected but are ignored.
    FilterChip(
        selected = MaintenanceMonthDay.LAST in draft.daysOfMonth,
        onClick = { onEdit(DraftChange.ToggleMonthDay(MaintenanceMonthDay.LAST)) },
        label = { Text(stringResource(R.string.maintenance_last_day)) },
    )
    ErrorLine(errors.firstOrNull { it == MaintenanceFieldError.DAYS_REQUIRED })
}

@Composable
private fun TimeWindow(
    draft: MaintenanceDraft,
    onEdit: (DraftChange) -> Unit,
) {
    var editing by rememberSaveable { mutableStateOf<Boolean?>(null) } // true = start, false = end
    val locale = currentLocale()
    Text(stringResource(R.string.maintenance_field_time_window), style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ValueRow(
            label = stringResource(R.string.maintenance_start_time),
            value = formatTimeOfDay(draft.startTime, locale),
            onClick = { editing = true },
            modifier = Modifier.weight(1f),
        )
        ValueRow(
            label = stringResource(R.string.maintenance_end_time),
            value = formatTimeOfDay(draft.endTime, locale),
            onClick = { editing = false },
            modifier = Modifier.weight(1f),
            supporting =
                if (!draft.endTime.isAfter(
                        draft.startTime,
                    )
                ) {
                    stringResource(R.string.maintenance_next_day)
                } else {
                    null
                },
        )
    }
    editing?.let { start ->
        TimeOfDayDialog(
            initial = if (start) draft.startTime else draft.endTime,
            onDismiss = { editing = null },
            onPicked = { time: LocalTime ->
                editing = null
                onEdit(if (start) DraftChange.StartTime(time) else DraftChange.EndTime(time))
            },
        )
    }
}

@Composable
private fun TimezoneField(
    option: String,
    serverTimezone: String?,
    onEdit: (DraftChange) -> Unit,
) {
    var picking by rememberSaveable { mutableStateOf(false) }
    val value =
        when (option) {
            Maintenance.TIMEZONE_SAME_AS_SERVER -> {
                serverTimezone?.let { stringResource(R.string.maintenance_tz_same_as_server_named, it) }
                    ?: stringResource(R.string.maintenance_tz_same_as_server)
            }

            else -> {
                option
            }
        }
    ValueRow(label = stringResource(R.string.maintenance_field_timezone), value = value, onClick = { picking = true })
    if (picking) {
        TimeZoneDialog(
            selected = option,
            serverTimezone = serverTimezone,
            onDismiss = { picking = false },
            onPicked = {
                picking = false
                onEdit(DraftChange.Timezone(it))
            },
        )
    }
}

@Composable
private fun DateRange(
    draft: MaintenanceDraft,
    errors: Set<MaintenanceFieldError>,
    onEdit: (DraftChange) -> Unit,
) {
    val single = draft.strategy == MaintenanceStrategy.SINGLE
    if (!single) {
        Text(
            stringResource(R.string.maintenance_field_date_range),
            style = MaterialTheme.typography.labelLarge,
        )
    }
    DateTimeRow(
        label = R.string.maintenance_start_date_time,
        value = draft.startDate,
        clearable = !single,
        error = errors.firstOrNull { it == MaintenanceFieldError.START_REQUIRED },
        onChange = { onEdit(DraftChange.StartDate(it)) },
    )
    DateTimeRow(
        label = R.string.maintenance_end_date_time,
        value = draft.endDate,
        clearable = !single,
        error =
            errors.firstOrNull {
                it == MaintenanceFieldError.END_REQUIRED || it == MaintenanceFieldError.END_BEFORE_START
            },
        onChange = { onEdit(DraftChange.EndDate(it)) },
        fallback = draft.startDate?.plusHours(1),
    )
}

@Composable
private fun DateTimeRow(
    @StringRes label: Int,
    value: LocalDateTime?,
    clearable: Boolean,
    error: MaintenanceFieldError?,
    onChange: (LocalDateTime?) -> Unit,
    fallback: LocalDateTime? = null,
) {
    var picking by rememberSaveable { mutableStateOf(false) }
    val locale = currentLocale()
    ValueRow(
        label = stringResource(label),
        value = value?.let { formatWallClock(it, locale) } ?: stringResource(R.string.maintenance_not_set),
        onClick = { picking = true },
        isError = error != null,
        supporting = error?.let { stringResource(it.messageRes()) },
        trailing =
            if (clearable && value != null) {
                {
                    IconButton(onClick = { onChange(null) }) {
                        Icon(Icons.Outlined.Clear, stringResource(R.string.maintenance_clear))
                    }
                }
            } else {
                null
            },
    )
    if (picking) {
        DateTimePickerDialog(
            initial = value ?: fallback ?: LocalDateTime.now().withSecond(0).withNano(0),
            onDismiss = { picking = false },
            onPicked = {
                picking = false
                onChange(it)
            },
        )
    }
}

@Composable
private fun QuickDurations(
    draft: MaintenanceDraft,
    onEdit: (DraftChange) -> Unit,
) {
    val start = draft.startDate
    val end = draft.endDate
    val current = if (start != null && end != null) Duration.between(start, end).toMinutes() else null
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        QUICK_DURATIONS.forEach { minutes ->
            FilterChip(
                selected = current == minutes,
                enabled = start != null,
                onClick = { onEdit(DraftChange.QuickDuration(minutes)) },
                label = {
                    Text(
                        if (minutes < MINUTES_PER_HOUR) {
                            stringResource(R.string.maintenance_quick_minutes, minutes.toInt())
                        } else {
                            stringResource(R.string.maintenance_quick_hours, (minutes / MINUTES_PER_HOUR).toInt())
                        },
                    )
                },
            )
        }
    }
    Text(
        stringResource(R.string.maintenance_quick_duration_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@StringRes
private fun MaintenanceFieldError.messageRes(): Int =
    when (this) {
        MaintenanceFieldError.TITLE_REQUIRED -> R.string.maintenance_error_title_required
        MaintenanceFieldError.TITLE_TOO_LONG -> R.string.maintenance_error_title_too_long
        MaintenanceFieldError.START_REQUIRED -> R.string.maintenance_error_start_required
        MaintenanceFieldError.END_REQUIRED -> R.string.maintenance_error_end_required
        MaintenanceFieldError.END_BEFORE_START -> R.string.maintenance_error_end_before_start
        MaintenanceFieldError.CRON_INVALID -> R.string.maintenance_error_cron
        MaintenanceFieldError.DURATION_INVALID -> R.string.maintenance_error_duration
        MaintenanceFieldError.INTERVAL_INVALID -> R.string.maintenance_error_interval
        MaintenanceFieldError.WEEKDAYS_REQUIRED -> R.string.maintenance_error_weekdays
        MaintenanceFieldError.DAYS_REQUIRED -> R.string.maintenance_error_days
        MaintenanceFieldError.TARGETS_REQUIRED -> R.string.maintenance_error_targets
    }

private fun previewState(draft: MaintenanceDraft) =
    MaintenanceEditUiState(
        loading = false,
        draft = draft,
        monitors = listOf(MonitorOption(1, "Website", true), MonitorOption(2, "Infra / Database", true)),
        statusPages = PreviewData.statusPages,
        serverTimezone = "Europe/Berlin",
    )

@Preview(showBackground = true, heightDp = 1400)
@Preview(showBackground = true, heightDp = 1400, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun MaintenanceEditSinglePreview() {
    KumaTheme(dynamicColor = false) {
        MaintenanceEditScreen(
            state =
                previewState(
                    MaintenanceDraft(
                        title = "Database upgrade",
                        startDate = LocalDateTime.of(2026, 11, 2, 22, 0),
                        endDate = LocalDateTime.of(2026, 11, 2, 23, 0),
                        monitorIds = setOf(1, 2),
                        statusPageIds = setOf(1),
                    ),
                ),
            actions = MaintenanceEditActions(),
        )
    }
}

@Preview(showBackground = true, heightDp = 1400)
@Composable
private fun MaintenanceEditRecurringPreview() {
    KumaTheme(dynamicColor = false) {
        MaintenanceEditScreen(
            state =
                previewState(
                    MaintenanceDraft(
                        id = 2,
                        strategy = MaintenanceStrategy.RECURRING_DAY_OF_MONTH,
                        daysOfMonth = setOf(MaintenanceMonthDay.Day(1), MaintenanceMonthDay.LAST),
                        startTime = LocalTime.of(23, 30),
                        endTime = LocalTime.of(0, 30),
                    ),
                ).copy(isNew = false, showErrors = true),
            actions = MaintenanceEditActions(),
        )
    }
}

@Preview(showBackground = true, heightDp = 1000)
@Composable
private fun MaintenanceEditCronPreview() {
    KumaTheme(dynamicColor = false) {
        MaintenanceEditScreen(
            state =
                previewState(
                    MaintenanceDraft(title = "Nightly", strategy = MaintenanceStrategy.CRON, monitorIds = setOf(1)),
                ),
            actions = MaintenanceEditActions(),
        )
    }
}
