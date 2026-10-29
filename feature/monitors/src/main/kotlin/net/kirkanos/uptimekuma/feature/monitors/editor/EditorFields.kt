package net.kirkanos.uptimekuma.feature.monitors.editor

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.kirkanos.uptimekuma.core.domain.editor.FieldIssue
import net.kirkanos.uptimekuma.core.domain.editor.FieldKind
import net.kirkanos.uptimekuma.core.domain.editor.FieldSpec
import net.kirkanos.uptimekuma.core.domain.editor.JsonValues
import net.kirkanos.uptimekuma.core.domain.editor.MonitorFieldRules
import net.kirkanos.uptimekuma.core.domain.editor.asBoolean
import net.kirkanos.uptimekuma.core.domain.editor.asDouble
import net.kirkanos.uptimekuma.core.domain.editor.asId
import net.kirkanos.uptimekuma.core.model.EditorPickers
import net.kirkanos.uptimekuma.core.model.PickerOption
import net.kirkanos.uptimekuma.feature.monitors.R

/** Everything a field widget needs besides its own value. */
@Immutable
internal class FieldContext(
    val values: Map<String, Any?>,
    val pickers: EditorPickers,
    val excludedParents: Set<Long>,
    val pushUrl: String?,
    val onChange: (String, Any?) -> Unit,
    val onRegeneratePushToken: () -> Unit,
) {
    val type: String get() = values["type"] as? String ?: "http"
    val subtype: String? get() = values["subtype"] as? String
}

private val durationFields = setOf("interval", "retryInterval", "timeout", "ping_per_request_timeout")
private val monospaceFields =
    setOf(
        "body",
        "headers",
        "grpcBody",
        "grpcMetadata",
        "grpcProtobuf",
        "databaseQuery",
        "tlsCert",
        "tlsKey",
        "tlsCa",
        "sshPrivateKey",
    )
private val statusCodeRanges = listOf("100-199", "200-299", "300-399", "400-499", "500-599")
private val kafkaMechanisms = listOf("None", "plain", "scram-sha-256", "scram-sha-512", "aws")

/** Text shown for a stored value (numbers without trailing `.0`, lists one per line). */
internal fun displayText(value: Any?): String =
    when (value) {
        null -> ""
        is Double -> if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
        is Float -> displayText(value.toDouble())
        is List<*> -> value.filterNotNull().joinToString("\n") { displayText(it) }
        else -> value.toString()
    }

/** One form field, rendered by its spec kind. */
@Composable
internal fun EditorField(
    field: FieldSpec,
    issue: FieldIssue?,
    ctx: FieldContext,
    modifier: Modifier = Modifier,
) {
    val value = ctx.values[field.name]
    val label = fieldLabel(field.name)
    val required = MonitorFieldRules.isRequired(field, ctx.type)
    val onValue: (Any?) -> Unit = { ctx.onChange(field.name, it) }
    when (field.kind) {
        FieldKind.CHECKBOX -> {
            SwitchRow(label, value.asBoolean(), onValue, modifier)
        }

        FieldKind.SELECT -> {
            SelectInput(field, label, value, ctx, onValue, modifier)
        }

        FieldKind.NUMBER -> {
            NumberInput(field.name, label, value, issue, required, onValue, modifier)
        }

        FieldKind.PASSWORD -> {
            TextInput(label, value, issue, required, onValue, modifier, password = true)
        }

        FieldKind.URL -> {
            TextInput(label, value, issue, required, onValue, modifier, keyboardType = KeyboardType.Uri)
        }

        FieldKind.MULTILINE, FieldKind.JSON -> {
            MultilineInput(
                field.name,
                label,
                value,
                issue,
                required,
                onValue,
                modifier,
                json =
                    field.kind == FieldKind.JSON,
            )
        }

        FieldKind.HEADERS -> {
            HeadersInput(label, value, issue, onValue, modifier)
        }

        FieldKind.STRING_LIST -> {
            StringListInput(label, value, issue, required, onValue, modifier)
        }

        FieldKind.STATUS_CODES -> {
            StatusCodesInput(label, value, issue, ctx.type, onValue, modifier)
        }

        FieldKind.NOTIFICATIONS -> {
            NotificationsInput(value, ctx.pickers.notifications, onValue, modifier)
        }

        FieldKind.PROXY -> {
            ProxyInput(value, ctx.pickers.proxies, onValue, modifier)
        }

        FieldKind.PARENT -> {
            IdSelect(
                label,
                value,
                ctx.pickers.groups.filterNot { it.id in ctx.excludedParents },
                issue,
                onValue,
                modifier,
            )
        }

        FieldKind.DOCKER_HOST -> {
            IdSelect(label, value, ctx.pickers.dockerHosts, issue, onValue, modifier, emptyHint = true)
        }

        FieldKind.REMOTE_BROWSER -> {
            IdSelect(label, value, ctx.pickers.remoteBrowsers, issue, onValue, modifier)
        }

        FieldKind.CONDITIONS -> {
            ConditionsEditor(ctx.type, value, onValue, modifier)
        }

        FieldKind.KAFKA_SASL -> {
            KafkaSaslInput(value, onValue, modifier)
        }

        FieldKind.PUSH_TOKEN -> {
            PushTokenInput(ctx.pushUrl, ctx.onRegeneratePushToken, modifier)
        }

        FieldKind.TAGS, FieldKind.HIDDEN -> {
            Unit
        }

        FieldKind.TEXT -> {
            TextInput(label, value, issue, required, onValue, modifier, placeholder = field.placeholder)
        }
    }
}

@Composable
private fun SupportingText(
    issue: FieldIssue?,
    hint: String? = null,
) {
    when {
        issue != null -> Text(issueText(issue))
        hint != null -> Text(hint)
    }
}

private fun Modifier.errorSemantics(
    issue: FieldIssue?,
    text: String,
): Modifier = if (issue != null) semantics { error(text) } else this

@Composable
private fun TextInput(
    label: String,
    value: Any?,
    issue: FieldIssue?,
    required: Boolean,
    onValue: (Any?) -> Unit,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    placeholder: String? = null,
    hint: String? = null,
) {
    var reveal by rememberSaveable { mutableStateOf(false) }
    val errorText = issue?.let { issueText(it) }.orEmpty()
    OutlinedTextField(
        value = displayText(value),
        onValueChange = onValue,
        label = { Text(if (required) stringResource(R.string.editor_required_label, label) else label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = true,
        isError = issue != null,
        supportingText = if (issue != null || hint != null) ({ SupportingText(issue, hint) }) else null,
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
                                    if (reveal) R.string.editor_hide_password else R.string.editor_show_password,
                                ),
                        )
                    }
                }
            } else {
                null
            },
        modifier = modifier.fillMaxWidth().errorSemantics(issue, errorText),
    )
}

@Composable
private fun NumberInput(
    name: String,
    label: String,
    value: Any?,
    issue: FieldIssue?,
    required: Boolean,
    onValue: (Any?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val seconds = value.asDouble()?.takeIf { name in durationFields }
    val hint = seconds?.let { durationText(it) }
    TextInput(
        label = if (name in durationFields) stringResource(R.string.editor_seconds_label, label) else label,
        value = value,
        issue = issue,
        required = required,
        onValue = onValue,
        modifier = modifier,
        keyboardType = if (name == "timeout") KeyboardType.Decimal else KeyboardType.Number,
        hint = hint,
    )
}

/** "1 min 30 s" style hint for a number of seconds. */
@Composable
private fun durationText(seconds: Double): String {
    val total = seconds.toLong()
    val hours = total / SECONDS_PER_HOUR
    val minutes = (total % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
    val rest = total % SECONDS_PER_MINUTE
    val parts =
        buildList {
            if (hours > 0) add(pluralStringResource(R.plurals.editor_duration_hours, hours.toInt(), hours))
            if (minutes > 0) add(pluralStringResource(R.plurals.editor_duration_minutes, minutes.toInt(), minutes))
            if (rest > 0 || isEmpty()) add(pluralStringResource(R.plurals.editor_duration_seconds, rest.toInt(), rest))
        }
    return parts.joinToString(" ")
}

private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3_600L

@Composable
private fun MultilineInput(
    name: String,
    label: String,
    value: Any?,
    issue: FieldIssue?,
    required: Boolean,
    onValue: (Any?) -> Unit,
    modifier: Modifier = Modifier,
    json: Boolean = false,
) {
    val errorText = issue?.let { issueText(it) }.orEmpty()
    Column(modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = displayText(value),
            onValueChange = onValue,
            label = { Text(if (required) stringResource(R.string.editor_required_label, label) else label) },
            minLines = 3,
            maxLines = 12,
            isError = issue != null,
            supportingText = issue?.let { { SupportingText(it) } },
            textStyle =
                if (name in monospaceFields) {
                    MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
                } else {
                    MaterialTheme.typography.bodyLarge
                },
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth().errorSemantics(issue, errorText),
        )
        if (json && value is String && value.isNotBlank()) {
            TextButton(onClick = { onValue(JsonValues.pretty(value)) }, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.editor_format_json))
            }
        }
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onValue: (Any?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .toggleable(value = checked, role = Role.Switch, onValueChange = { onValue(it) })
                .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f).padding(end = 12.dp), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = null)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Dropdown(
    label: String,
    selectedText: String,
    options: List<T>,
    optionText: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    issue: FieldIssue? = null,
) {
    var expanded by remember { mutableStateOf(false) }
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
            supportingText = issue?.let { { SupportingText(it) } },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionText(option)) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun SelectInput(
    field: FieldSpec,
    label: String,
    value: Any?,
    ctx: FieldContext,
    onValue: (Any?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = field.optionsFor(ctx.type, ctx.subtype).map { if (it is Int) it.toLong() else it }
    val current = if (value is Int) value.toLong() else value
    val all = if (field.name == "manual_status" && null !in options) listOf<Any?>(null) + options else options
    Dropdown(
        label = label,
        selectedText = optionLabel(field.name, current),
        options = all,
        optionText = { optionLabel(field.name, it) },
        onSelect = onValue,
        modifier = modifier,
    )
}

@Composable
private fun IdSelect(
    label: String,
    value: Any?,
    options: List<PickerOption>,
    issue: FieldIssue?,
    onValue: (Any?) -> Unit,
    modifier: Modifier = Modifier,
    emptyHint: Boolean = false,
) {
    val id = value.asId()
    val none = stringResource(R.string.editor_option_none)
    Column(modifier) {
        Dropdown(
            label = label,
            selectedText = options.firstOrNull { it.id == id }?.label ?: if (id != null) "#$id" else none,
            options = listOf<PickerOption?>(null) + options,
            optionText = { it?.label ?: none },
            onSelect = { onValue(it?.id) },
            issue = issue,
        )
        if (emptyHint && options.isEmpty()) {
            Text(
                stringResource(R.string.editor_docker_hosts_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ProxyInput(
    value: Any?,
    proxies: List<PickerOption>,
    onValue: (Any?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected = value.asId()
    Column(modifier.fillMaxWidth()) {
        if (proxies.isEmpty()) {
            Text(
                stringResource(R.string.editor_proxies_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        (listOf<PickerOption?>(null) + proxies).forEach { proxy ->
            val isSelected = proxy?.id == selected
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onValue(proxy?.id) })
                        .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = isSelected, onClick = null)
                Text(
                    proxy?.label ?: stringResource(R.string.editor_proxy_none),
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun NotificationsInput(
    value: Any?,
    notifications: List<PickerOption>,
    onValue: (Any?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected =
        (value as? Map<*, *>)
            .orEmpty()
            .filterValues { it.asBoolean() }
            .keys
            .mapNotNull { it.asId() }
            .toSet()
    Column(modifier.fillMaxWidth()) {
        if (notifications.isEmpty()) {
            Text(
                stringResource(R.string.editor_notifications_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        notifications.forEach { n ->
            val checked = n.id in selected
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .toggleable(value = checked, role = Role.Checkbox) { on ->
                            val ids = if (on) selected + n.id else selected - n.id
                            onValue(ids.associate { it.toString() to true })
                        }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = checked, onCheckedChange = null)
                Text(n.label, modifier = Modifier.weight(1f).padding(start = 12.dp))
                if (n.isDefault) {
                    Text(
                        stringResource(R.string.editor_notification_default),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusCodesInput(
    label: String,
    value: Any?,
    issue: FieldIssue?,
    type: String,
    onValue: (Any?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val codes = (value as? List<*>).orEmpty().map { displayText(it) }
    val presets = if (type == "websocket-upgrade") listOf("1000") else statusCodeRanges
    var custom by rememberSaveable { mutableStateOf("") }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            presets.forEach { code ->
                val on = code in codes
                FilterChip(
                    selected = on,
                    onClick = { onValue(if (on) codes - code else codes + code) },
                    label = { Text(code) },
                )
            }
            codes.filterNot { it in presets }.forEach { code ->
                InputChip(
                    selected = true,
                    onClick = { onValue(codes - code) },
                    label = { Text(code) },
                    trailingIcon = {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.editor_remove_item, code),
                        )
                    },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = custom,
                onValueChange = { custom = it },
                label = { Text(stringResource(R.string.editor_status_code_add_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = {
                    val code = custom.trim()
                    if (code.isNotEmpty() && code !in codes) onValue(codes + code)
                    custom = ""
                },
                enabled = custom.isNotBlank(),
            ) { Text(stringResource(R.string.editor_add)) }
        }
        if (issue != null) {
            Text(issueText(issue), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun StringListInput(
    label: String,
    value: Any?,
    issue: FieldIssue?,
    required: Boolean,
    onValue: (Any?) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = displayText(value),
        // Kept as raw text while typing; the payload builder splits it into a list.
        onValueChange = onValue,
        label = { Text(if (required) stringResource(R.string.editor_required_label, label) else label) },
        minLines = 2,
        isError = issue != null,
        supportingText = { SupportingText(issue, stringResource(R.string.editor_one_per_line)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
        modifier = modifier.fillMaxWidth(),
    )
}

/** Header rows parsed from a JSON object string; `null` when the text is not a flat object. */
internal fun headerRows(text: String?): List<Pair<String, String>>? {
    if (text.isNullOrBlank()) return emptyList()
    val map = JsonValues.toKotlin(JsonValues.parseOrNull(text)) as? Map<*, *> ?: return null
    if (map.values.any { it is Map<*, *> || it is List<*> }) return null
    return map.entries.map { (k, v) -> k.toString() to displayText(v) }
}

internal fun headersJson(rows: List<Pair<String, String>>): String? {
    val filled = rows.filter { it.first.isNotBlank() }
    if (filled.isEmpty()) return null
    return JsonValues.pretty(JsonValues.stringify(filled.associate { it.first.trim() to it.second }))
}

@Composable
private fun HeadersInput(
    label: String,
    value: Any?,
    issue: FieldIssue?,
    onValue: (Any?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val text = value as? String
    val parsed = headerRows(text)
    var raw by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            if (parsed != null) {
                TextButton(onClick = { raw = !raw }) {
                    Text(stringResource(if (raw) R.string.editor_headers_as_list else R.string.editor_headers_as_json))
                }
            }
        }
        if (raw || parsed == null) {
            MultilineInput("headers", label, value, issue, required = false, onValue = onValue, json = true)
        } else {
            HeaderRows(parsed, onValue)
        }
    }
}

@Composable
private fun HeaderRows(
    rows: List<Pair<String, String>>,
    onValue: (Any?) -> Unit,
) {
    val editable = rows + ("" to "")
    editable.forEachIndexed { index, (key, headerValue) ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = key,
                onValueChange = { k ->
                    onValue(
                        headersJson(
                            editable.mapIndexed { i, r ->
                                if (i ==
                                    index
                                ) {
                                    k to r.second
                                } else {
                                    r
                                }
                            },
                        ),
                    )
                },
                label = { Text(stringResource(R.string.editor_header_name)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = headerValue,
                onValueChange = { v ->
                    onValue(
                        headersJson(
                            editable.mapIndexed { i, r ->
                                if (i ==
                                    index
                                ) {
                                    r.first to v
                                } else {
                                    r
                                }
                            },
                        ),
                    )
                },
                label = { Text(stringResource(R.string.editor_header_value)) },
                singleLine = true,
                enabled = key.isNotBlank(),
                modifier = Modifier.weight(1f),
            )
            if (index < rows.size) {
                IconButton(onClick = { onValue(headersJson(rows.filterIndexed { i, _ -> i != index })) }) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.editor_remove_item, key))
                }
            }
        }
    }
}

@Composable
private fun KafkaSaslInput(
    value: Any?,
    onValue: (Any?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = (value as? Map<*, *>).orEmpty().entries.associate { it.key.toString() to it.value }
    val mechanism = options["mechanism"] as? String ?: "None"
    val update: (String, Any?) -> Unit = { k, v -> onValue(options + (k to v)) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Dropdown(
            label = fieldLabel("kafkaProducerSaslOptions"),
            selectedText = mechanism,
            options = kafkaMechanisms,
            optionText = { it },
            onSelect = { m -> onValue(mapOf("mechanism" to m)) },
        )
        val fields =
            when (mechanism) {
                "aws" -> listOf("authorizationIdentity", "accessKeyId", "secretAccessKey", "sessionToken")
                "None" -> emptyList()
                else -> listOf("username", "password")
            }
        fields.forEach { key ->
            TextInput(
                label = key,
                value = options[key],
                issue = null,
                required = mechanism == "aws" && key != "sessionToken",
                onValue = { update(key, it) },
                password = key in setOf("password", "secretAccessKey", "sessionToken"),
            )
        }
    }
}

@Composable
private fun PushTokenInput(
    pushUrl: String?,
    onRegenerate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(fieldLabel("pushToken"), style = MaterialTheme.typography.bodyLarge)
        Text(
            text = pushUrl ?: stringResource(R.string.editor_push_url_unavailable),
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        )
        Text(
            stringResource(R.string.editor_push_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    val url = pushUrl ?: return@OutlinedButton
                    scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("push url", url))) }
                },
                enabled = pushUrl != null,
            ) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                Text(stringResource(R.string.editor_copy), modifier = Modifier.padding(start = 8.dp))
            }
            OutlinedButton(onClick = onRegenerate) {
                Icon(Icons.Outlined.Refresh, contentDescription = null)
                Text(stringResource(R.string.editor_reset_token), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}
