package net.kirkanos.uptimekuma.feature.maintenance.edit

import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import net.kirkanos.uptimekuma.core.model.Maintenance
import net.kirkanos.uptimekuma.core.ui.theme.KumaTheme
import net.kirkanos.uptimekuma.feature.maintenance.R
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset

/** Date picker followed by a time picker (Material 3). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DateTimePickerDialog(
    initial: LocalDateTime,
    onDismiss: () -> Unit,
    onPicked: (LocalDateTime) -> Unit,
) {
    var date by rememberSaveable { mutableStateOf<LocalDate?>(null) }
    val picked = date
    if (picked == null) {
        val state =
            rememberDatePickerState(
                initialSelectedDateMillis =
                    initial
                        .toLocalDate()
                        .atStartOfDay(
                            ZoneOffset.UTC,
                        ).toInstant()
                        .toEpochMilli(),
            )
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(
                    enabled = state.selectedDateMillis != null,
                    onClick = {
                        date =
                            state.selectedDateMillis?.let {
                                Instant
                                    .ofEpochMilli(
                                        it,
                                    ).atZone(ZoneOffset.UTC)
                                    .toLocalDate()
                            }
                    },
                ) { Text(stringResource(R.string.maintenance_next)) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.maintenance_cancel)) } },
        ) { DatePicker(state = state) }
    } else {
        TimeOfDayDialog(
            initial = initial.toLocalTime(),
            onDismiss = onDismiss,
            onPicked = { time -> onPicked(LocalDateTime.of(picked, time)) },
        )
    }
}

/** Material 3 time picker in a dialog, honouring the device's 12/24 h setting. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimeOfDayDialog(
    initial: LocalTime,
    onDismiss: () -> Unit,
    onPicked: (LocalTime) -> Unit,
) {
    val context = LocalContext.current
    val state = rememberTimePickerState(initial.hour, initial.minute, DateFormat.is24HourFormat(context))
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onPicked(LocalTime.of(state.hour, state.minute)) }) {
                Text(stringResource(R.string.maintenance_ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.maintenance_cancel)) } },
        text = { TimePicker(state = state) },
    )
}

/** Full-screen searchable list: same as server, UTC, then all IANA zones with their offset. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimeZoneDialog(
    selected: String,
    serverTimezone: String?,
    onDismiss: () -> Unit,
    onPicked: (String) -> Unit,
) {
    val zones = remember { EditorSupport.timeZones(Instant.now()) }
    var query by rememberSaveable { mutableStateOf("") }
    val sameAsServer =
        serverTimezone?.let { stringResource(R.string.maintenance_tz_same_as_server_named, it) }
            ?: stringResource(R.string.maintenance_tz_same_as_server)
    val fixed = listOf(Maintenance.TIMEZONE_SAME_AS_SERVER to sameAsServer, Maintenance.TIMEZONE_UTC to "UTC")
    val entryTemplate = stringResource(R.string.maintenance_tz_entry)
    val all = fixed + zones.map { it.id to entryTemplate.format(it.offset, it.id) }
    val filtered =
        remember(query, all) {
            val q = query.trim()
            if (q.isEmpty()) {
                all
            } else {
                all.filter {
                    it.second.contains(q, ignoreCase = true) ||
                        it.first.contains(q, true)
                }
            }
        }
    FullScreenDialog(title = stringResource(R.string.maintenance_field_timezone), onDismiss = onDismiss) {
        SearchField(query, { query = it }, stringResource(R.string.maintenance_tz_search))
        LazyColumn(Modifier.fillMaxSize().imePadding()) {
            items(filtered, key = { it.first }) { (id, label) ->
                ListItem(
                    modifier =
                        Modifier.selectable(selected = id == selected, role = Role.RadioButton) { onPicked(id) },
                    leadingContent = { RadioButton(selected = id == selected, onClick = null) },
                    headlineContent = { Text(label) },
                )
            }
        }
    }
}

/** Full-screen multi-select with search and select all (the web UI's VueMultiselect). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MonitorPickerDialog(
    options: List<MonitorOption>,
    selected: Set<Long>,
    onDismiss: () -> Unit,
    onConfirm: (Set<Long>) -> Unit,
) {
    var selection by rememberSaveable { mutableStateOf(selected.toList()) }
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(query, options) { options.filter { it.name.contains(query.trim(), ignoreCase = true) } }
    val allSelected = options.isNotEmpty() && options.all { it.id in selection }
    FullScreenDialog(
        title = stringResource(R.string.maintenance_pick_monitors),
        onDismiss = onDismiss,
        action = {
            TextButton(onClick = { onConfirm(selection.toSet()) }) { Text(stringResource(R.string.maintenance_done)) }
        },
    ) {
        SearchField(query, { query = it }, stringResource(R.string.maintenance_search_monitors))
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { selection = if (allSelected) emptyList() else options.map { it.id } }) {
                Text(
                    stringResource(
                        if (allSelected) R.string.maintenance_deselect_all else R.string.maintenance_select_all,
                    ),
                )
            }
        }
        HorizontalDivider()
        if (filtered.isEmpty()) {
            Text(
                stringResource(R.string.maintenance_no_monitors_match),
                modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(Modifier.fillMaxSize().imePadding()) {
            items(filtered, key = { it.id }) { option ->
                val checked = option.id in selection
                ListItem(
                    modifier =
                        Modifier.toggleable(value = checked, role = Role.Checkbox) {
                            selection = if (checked) selection - option.id else selection + option.id
                        },
                    leadingContent = { Checkbox(checked = checked, onCheckedChange = null) },
                    headlineContent = { Text(option.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent =
                        if (option.active) null else ({ Text(stringResource(R.string.maintenance_monitor_paused)) }),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FullScreenDialog(
    title: String,
    onDismiss: () -> Unit,
    action: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Outlined.Close, stringResource(R.string.maintenance_cancel))
                        }
                    },
                    actions = { action() },
                )
            },
        ) { padding -> Column(Modifier.padding(padding).fillMaxSize()) { content() } }
    }
}

@Composable
private fun SearchField(
    query: String,
    onChange: (String) -> Unit,
    label: String,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        label = { Text(label) },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** A tappable row showing a label and a value, used for date, time and zone fields. */
@Composable
internal fun ValueRow(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    supporting: String? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        tonalElevation = 1.dp,
    ) {
        val accent = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = accent,
                )
                Text(value, style = MaterialTheme.typography.bodyLarge)
                if (supporting != null) {
                    Text(
                        supporting,
                        style = MaterialTheme.typography.bodySmall,
                        color = accent,
                    )
                }
            }
            trailing?.invoke()
        }
    }
}

@Preview(showBackground = true, heightDp = 600)
@Composable
private fun MonitorPickerPreview() {
    KumaTheme(dynamicColor = false) {
        MonitorPickerDialog(
            options = listOf(MonitorOption(1, "Website", true), MonitorOption(2, "Infra / Database", false)),
            selected = setOf(1L),
            onDismiss = {},
            onConfirm = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ValueRowPreview() {
    KumaTheme(dynamicColor = false) {
        ValueRow(label = "Start time", value = "02:00", onClick = {}, supporting = "Ends on the next day")
    }
}
