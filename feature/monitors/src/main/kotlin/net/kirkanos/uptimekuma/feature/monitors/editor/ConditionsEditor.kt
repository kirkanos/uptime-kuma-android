package net.kirkanos.uptimekuma.feature.monitors.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.kirkanos.uptimekuma.core.domain.editor.MonitorSpec
import net.kirkanos.uptimekuma.feature.monitors.R

/** A new expression for [variable] with its first operator. */
internal fun newCondition(
    spec: MonitorSpec,
    type: String,
): Map<String, Any?> {
    val variable = spec.type(type)?.conditionVariables?.firstOrNull()
    return mapOf(
        "type" to "expression",
        "variable" to variable?.id,
        "operator" to variable?.operatorIds(spec)?.firstOrNull(),
        "value" to "",
        "andOr" to "and",
    )
}

private val operatorLabels =
    mapOf(
        "equals" to R.string.editor_op_equals,
        "not_equals" to R.string.editor_op_not_equals,
        "contains" to R.string.editor_op_contains,
        "not_contains" to R.string.editor_op_not_contains,
        "starts_with" to R.string.editor_op_starts_with,
        "not_starts_with" to R.string.editor_op_not_starts_with,
        "ends_with" to R.string.editor_op_ends_with,
        "not_ends_with" to R.string.editor_op_not_ends_with,
        "num_equals" to R.string.editor_op_num_equals,
        "num_not_equals" to R.string.editor_op_num_not_equals,
        "lt" to R.string.editor_op_lt,
        "gt" to R.string.editor_op_gt,
        "lte" to R.string.editor_op_lte,
        "gte" to R.string.editor_op_gte,
    )

/**
 * Editor for monitor conditions (Kuma 2.x). Top-level expressions are editable (variable, operator, value,
 * AND/OR); nested groups created in the web UI are kept unchanged and shown as a summary.
 */
@Composable
internal fun ConditionsEditor(
    type: String,
    value: Any?,
    onValue: (Any?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spec = MonitorSpec.bundled
    val items = (value as? List<*>).orEmpty()
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(fieldLabel("conditions"), style = MaterialTheme.typography.bodyLarge)
        Text(
            stringResource(R.string.editor_conditions_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        items.forEachIndexed { index, item ->
            val map = (item as? Map<*, *>).orEmpty().entries.associate { it.key.toString() to it.value }
            val replace: (Map<String, Any?>?) -> Unit = { updated ->
                onValue(items.toMutableList().apply { if (updated == null) removeAt(index) else set(index, updated) })
            }
            if (map["type"] == "group") {
                GroupSummary(map, onRemove = { replace(null) })
            } else {
                ExpressionCard(spec, type, map, first = index == 0, onChange = replace)
            }
        }
        OutlinedButton(onClick = { onValue(items + newCondition(spec, type)) }) {
            Icon(Icons.Outlined.Add, contentDescription = null)
            Text(stringResource(R.string.editor_condition_add), modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun GroupSummary(
    group: Map<String, Any?>,
    onRemove: () -> Unit,
) {
    val count = (group["children"] as? List<*>).orEmpty().size
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                pluralStringResource(R.plurals.editor_condition_group, count, count),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            IconButton(onClick = onRemove) {
                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.editor_condition_remove))
            }
        }
    }
}

@Composable
private fun ExpressionCard(
    spec: MonitorSpec,
    type: String,
    expression: Map<String, Any?>,
    first: Boolean,
    onChange: (Map<String, Any?>?) -> Unit,
) {
    val variables = spec.type(type)?.conditionVariables.orEmpty()
    val variable = variables.firstOrNull { it.id == expression["variable"] } ?: variables.firstOrNull()
    val operators = variable?.operatorIds(spec).orEmpty()
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!first) {
                val andOr = expression["andOr"] as? String ?: "and"
                SingleChoiceSegmentedButtonRow {
                    listOf("and", "or").forEachIndexed { i, option ->
                        SegmentedButton(
                            selected = andOr == option,
                            onClick = { onChange(expression + ("andOr" to option)) },
                            shape = SegmentedButtonDefaults.itemShape(i, 2),
                        ) {
                            Text(
                                stringResource(
                                    if (option ==
                                        "and"
                                    ) {
                                        R.string.editor_condition_and
                                    } else {
                                        R.string.editor_condition_or
                                    },
                                ),
                            )
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                MenuButton(
                    text = variable?.id.orEmpty(),
                    options = variables.map { it.id },
                    optionText = { it },
                    onSelect = { id ->
                        val ops = variables.first { it.id == id }.operatorIds(spec)
                        val op = expression["operator"].takeIf { it in ops } ?: ops.firstOrNull()
                        onChange(expression + mapOf("variable" to id, "operator" to op))
                    },
                )
                MenuButton(
                    text =
                        (expression["operator"] as? String)
                            ?.let { op ->
                                operatorLabels[op]?.let { stringResource(it) }
                                    ?: op
                            }.orEmpty(),
                    options = operators,
                    optionText = { op -> operatorLabels[op]?.let { stringResource(it) } ?: op },
                    onSelect = { onChange(expression + ("operator" to it)) },
                )
                Row(Modifier.weight(1f)) {}
                IconButton(onClick = { onChange(null) }) {
                    Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.editor_condition_remove))
                }
            }
            OutlinedTextField(
                value = expression["value"]?.toString().orEmpty(),
                onValueChange = { onChange(expression + ("value" to it)) },
                label = { Text(stringResource(R.string.editor_condition_value)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun <T> MenuButton(
    text: String,
    options: List<T>,
    optionText: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Column {
        TextButton(onClick = { open = true }) { Text(text.ifEmpty { "…" }) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionText(option)) },
                    onClick = {
                        open = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}
