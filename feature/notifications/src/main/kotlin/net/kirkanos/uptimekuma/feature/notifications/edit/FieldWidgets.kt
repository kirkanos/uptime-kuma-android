package net.kirkanos.uptimekuma.feature.notifications.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import net.kirkanos.uptimekuma.core.domain.editor.JsonValues
import net.kirkanos.uptimekuma.core.domain.notification.Condition
import net.kirkanos.uptimekuma.core.domain.notification.FieldOption
import net.kirkanos.uptimekuma.core.domain.notification.NotificationForm
import net.kirkanos.uptimekuma.core.domain.notification.NotificationIssue
import net.kirkanos.uptimekuma.core.domain.notification.ProviderFieldKind
import net.kirkanos.uptimekuma.core.domain.notification.ProviderFieldSpec
import net.kirkanos.uptimekuma.core.domain.notification.ProviderSpec
import net.kirkanos.uptimekuma.feature.notifications.FieldLabels
import net.kirkanos.uptimekuma.feature.notifications.ProviderTexts
import net.kirkanos.uptimekuma.feature.notifications.R

/** Text shown for a stored value (numbers without trailing `.0`, lists one per line). */
internal fun displayText(value: Any?): String =
    when (value) {
        null -> ""
        is Double -> if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
        is List<*> -> value.filterNotNull().joinToString("\n") { displayText(it) }
        is Map<*, *> -> JsonValues.stringify(value)
        else -> value.toString()
    }

@Composable
internal fun fieldLabel(
    provider: ProviderSpec,
    field: ProviderFieldSpec,
): String =
    ProviderTexts.field(provider.id, field)?.label?.let { stringResource(it) }
        ?: FieldLabels.humanize(provider.id, field.name)

@Composable
private fun fieldHelp(
    provider: ProviderSpec,
    field: ProviderFieldSpec,
): String? {
    val res =
        ProviderTexts.field(provider.id, field)?.help
            ?: R.string.notif_help_template.takeIf {
                field.kind == ProviderFieldKind.TEMPLATE || field.kind == ProviderFieldKind.TEMPLATE_MULTILINE
            }
    return res?.let { stringResource(it) }
}

@Composable
internal fun issueText(issue: NotificationIssue): String =
    when (issue.kind) {
        NotificationIssue.Kind.REQUIRED -> {
            stringResource(R.string.notif_issue_required)
        }

        NotificationIssue.Kind.NUMBER -> {
            stringResource(R.string.notif_issue_number)
        }

        NotificationIssue.Kind.RANGE -> {
            val min = issue.min?.let(::displayText)
            val max = issue.max?.let(::displayText)
            when {
                min != null && max != null -> stringResource(R.string.notif_issue_range, min, max)
                min != null -> stringResource(R.string.notif_issue_min, min)
                else -> stringResource(R.string.notif_issue_max, max.orEmpty())
            }
        }

        NotificationIssue.Kind.URL -> {
            stringResource(R.string.notif_issue_url)
        }

        NotificationIssue.Kind.EMAIL -> {
            stringResource(R.string.notif_issue_email)
        }

        NotificationIssue.Kind.JSON -> {
            stringResource(R.string.notif_issue_json)
        }

        NotificationIssue.Kind.PATTERN -> {
            stringResource(R.string.notif_issue_pattern)
        }
    }

/** One provider field, rendered by its spec kind. */
@Composable
internal fun ProviderField(
    provider: ProviderSpec,
    field: ProviderFieldSpec,
    value: Any?,
    issue: NotificationIssue?,
    onValue: (Any?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = fieldLabel(provider, field)
    val help = fieldHelp(provider, field)
    val required = field.required && field.kind != ProviderFieldKind.CHECKBOX
    val shownLabel = if (required) stringResource(R.string.notif_required_label, label) else label
    when (field.kind) {
        ProviderFieldKind.CHECKBOX -> {
            SwitchRow(label, Condition.truthy(value), { onValue(it) }, modifier, help)
        }

        ProviderFieldKind.SELECT -> {
            SelectInput(provider, field, shownLabel, value, issue, help, onValue, modifier)
        }

        ProviderFieldKind.TEXTAREA, ProviderFieldKind.TEMPLATE_MULTILINE, ProviderFieldKind.LIST -> {
            TextInput(
                shownLabel,
                value,
                issue,
                onValue,
                modifier,
                help = help ?: stringResource(R.string.notif_list_hint).takeIf { field.kind == ProviderFieldKind.LIST },
                placeholder = field.placeholder,
                multiline = true,
                monospace = field.kind == ProviderFieldKind.TEMPLATE_MULTILINE,
            )
        }

        ProviderFieldKind.JSON_OBJECT -> {
            HeadersInput(shownLabel, value, issue, help, onValue, modifier)
        }

        ProviderFieldKind.UNSUPPORTED -> {
            InfoText(stringResource(R.string.notif_unsupported_field, label), modifier)
        }

        else -> {
            TextInput(
                shownLabel,
                value,
                issue,
                onValue,
                modifier,
                help = help,
                placeholder = field.placeholder,
                password = field.kind == ProviderFieldKind.PASSWORD,
                keyboardType =
                    when (field.kind) {
                        ProviderFieldKind.NUMBER -> KeyboardType.Number
                        ProviderFieldKind.URL -> KeyboardType.Uri
                        ProviderFieldKind.EMAIL -> KeyboardType.Email
                        else -> KeyboardType.Text
                    },
                monospace = field.kind == ProviderFieldKind.TEMPLATE,
            )
        }
    }
}

@Composable
internal fun InfoText(
    text: String,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Icon(
            Icons.Outlined.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp),
        )
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun Modifier.errorSemantics(text: String?): Modifier = if (text != null) semantics { error(text) } else this

@Composable
internal fun TextInput(
    label: String,
    value: Any?,
    issue: NotificationIssue?,
    onValue: (Any?) -> Unit,
    modifier: Modifier = Modifier,
    help: String? = null,
    placeholder: String? = null,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    multiline: Boolean = false,
    monospace: Boolean = false,
) {
    var reveal by rememberSaveable { mutableStateOf(false) }
    val error = issue?.let { issueText(it) }
    val supporting = error ?: help
    OutlinedTextField(
        value = displayText(value),
        onValueChange = onValue,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it, maxLines = if (multiline) 4 else 1) } },
        singleLine = !multiline,
        minLines = if (multiline) 3 else 1,
        maxLines = if (multiline) 12 else 1,
        isError = issue != null,
        supportingText = supporting?.let { { Text(it) } },
        textStyle =
            if (monospace) {
                MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
            } else {
                MaterialTheme.typography.bodyLarge
            },
        visualTransformation = if (password && !reveal) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions =
            KeyboardOptions(
                keyboardType = if (password) KeyboardType.Password else keyboardType,
                autoCorrectEnabled = false,
            ),
        trailingIcon =
            if (password) {
                {
                    IconButton(onClick = { reveal = !reveal }) {
                        Icon(
                            if (reveal) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription =
                                stringResource(
                                    if (reveal) R.string.notif_hide_password else R.string.notif_show_password,
                                ),
                        )
                    }
                }
            } else {
                null
            },
        modifier = modifier.fillMaxWidth().errorSemantics(error),
    )
}

@Composable
internal fun SwitchRow(
    label: String,
    checked: Boolean,
    onValue: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    help: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onValue)
                .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (help != null) {
                Text(
                    help,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** Label of a select option: hand-written translation, else the (English) label from the spec. */
@Composable
private fun optionLabel(
    provider: ProviderSpec,
    field: ProviderFieldSpec,
    option: FieldOption,
): String =
    ProviderTexts.options["${provider.id}.${field.name}"]
        ?.get(option.value.toString())
        ?.let { stringResource(it) }
        ?: option.label

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectInput(
    provider: ProviderSpec,
    field: ProviderFieldSpec,
    label: String,
    value: Any?,
    issue: NotificationIssue?,
    help: String?,
    onValue: (Any?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = NotificationForm.matchOption(field, value)
    val notSet = stringResource(R.string.notif_select_none)
    val selectedText =
        when {
            selected != null -> optionLabel(provider, field, selected)

            NotificationForm.isBlank(value) -> notSet

            // A value the spec does not list (legacy/newer Kuma): show it and keep it.
            else -> stringResource(R.string.notif_unknown_value, displayText(value))
        }
    val error = issue?.let { issueText(it) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = selectedText,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            isError = issue != null,
            supportingText = (error ?: help)?.let { { Text(it) } },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    .errorSemantics(error),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (!field.required) {
                DropdownMenuItem(
                    text = { Text(notSet) },
                    onClick = {
                        expanded = false
                        onValue(null)
                    },
                )
            }
            field.options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(provider, field, option)) },
                    onClick = {
                        expanded = false
                        onValue(option.value)
                    },
                )
            }
        }
    }
}

/** Header name/value rows of a JSON object string, `null` when [value] is not a flat object of strings. */
internal fun headerPairs(value: Any?): List<Pair<String, String>>? {
    if (NotificationForm.isBlank(value)) return emptyList()
    val parsed =
        when (value) {
            is Map<*, *> -> value
            is String -> JsonValues.toKotlin(JsonValues.parseOrNull(value)) as? Map<*, *>
            else -> null
        } ?: return null
    if (parsed.values.any { it is Map<*, *> || it is List<*> }) return null
    return parsed.entries.map { (k, v) -> k.toString() to displayText(v) }
}

/** Rows -> JSON object string; rows without a name are dropped, `""` when nothing is left. */
internal fun headersJson(rows: List<Pair<String, String>>): String {
    val named = rows.filter { it.first.isNotBlank() }
    return if (named.isEmpty()) "" else JsonValues.stringify(named.associate { it.first.trim() to it.second })
}

/** Additional headers: name/value rows, or the raw JSON text when it is not a flat object. */
@Composable
private fun HeadersInput(
    label: String,
    value: Any?,
    issue: NotificationIssue?,
    help: String?,
    onValue: (Any?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val initialPairs = remember { headerPairs(value) }
    var raw by rememberSaveable { mutableStateOf(initialPairs == null) }
    val rows = remember { mutableStateListOf<Pair<String, String>>().apply { addAll(initialPairs.orEmpty()) } }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            TextButton(
                onClick = {
                    if (raw) {
                        headerPairs(value)?.let {
                            rows.clear()
                            rows.addAll(it)
                            raw = false
                        }
                    } else {
                        raw = true
                    }
                },
                enabled = raw.not() || headerPairs(value) != null,
            ) {
                Text(stringResource(if (raw) R.string.notif_headers_edit_pairs else R.string.notif_headers_edit_json))
            }
        }
        if (raw) {
            TextInput(label, value, issue, onValue, help = help, multiline = true, monospace = true)
            return@Column
        }
        rows.forEachIndexed { index, (name, headerValue) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        rows[index] = it to headerValue
                        onValue(headersJson(rows))
                    },
                    label = { Text(stringResource(R.string.notif_headers_name)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = headerValue,
                    onValueChange = {
                        rows[index] = name to it
                        onValue(headersJson(rows))
                    },
                    label = { Text(stringResource(R.string.notif_headers_value)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = {
                    rows.removeAt(index)
                    onValue(headersJson(rows))
                }) {
                    Icon(Icons.Outlined.Close, stringResource(R.string.notif_headers_remove))
                }
            }
        }
        TextButton(onClick = { rows.add("" to "") }) {
            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
            Text(stringResource(R.string.notif_headers_add))
        }
        val supporting = issue?.let { issueText(it) } ?: help
        if (supporting != null) {
            Text(
                supporting,
                style = MaterialTheme.typography.bodySmall,
                color =
                    if (issue !=
                        null
                    ) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
        }
    }
}
