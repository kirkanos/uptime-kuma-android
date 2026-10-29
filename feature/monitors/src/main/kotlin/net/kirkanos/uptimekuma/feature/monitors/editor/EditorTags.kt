package net.kirkanos.uptimekuma.feature.monitors.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.kirkanos.uptimekuma.core.model.MonitorTag
import net.kirkanos.uptimekuma.core.model.Tag
import net.kirkanos.uptimekuma.core.ui.component.parseHexColor
import net.kirkanos.uptimekuma.core.ui.theme.KumaTheme
import net.kirkanos.uptimekuma.feature.monitors.R
import net.kirkanos.uptimekuma.feature.monitors.tags.TAG_COLORS
import net.kirkanos.uptimekuma.feature.monitors.tags.TagColorPicker

/** The editor's tag list: attached tags (removable), add button and a link to the tag manager. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TagsSection(
    tags: List<MonitorTag>,
    onAdd: () -> Unit,
    onRemove: (MonitorTag) -> Unit,
    onManageTags: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (tags.isEmpty()) {
            Text(
                stringResource(R.string.editor_tags_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            tags.forEach { tag ->
                val label = if (tag.value.isBlank()) tag.name else "${tag.name}: ${tag.value}"
                InputChip(
                    selected = false,
                    onClick = { onRemove(tag) },
                    label = { Text(label) },
                    leadingIcon = { ColorDot(tag.color) },
                    trailingIcon = {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.editor_remove_item, label),
                        )
                    },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onAdd) {
                Icon(Icons.Outlined.Add, contentDescription = null)
                Text(stringResource(R.string.editor_tag_add), modifier = Modifier.padding(start = 8.dp))
            }
            TextButton(onClick = onManageTags) { Text(stringResource(R.string.tags_manage)) }
        }
    }
}

@Composable
private fun ColorDot(color: String) {
    Surface(
        shape = CircleShape,
        color = parseHexColor(color) ?: Color.Gray,
        modifier = Modifier.size(12.dp),
    ) {}
}

/** Attach an existing tag (with optional value) or create a new one with a preset color. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddTagDialog(
    tags: List<Tag>,
    onAdd: (Tag, String) -> Unit,
    onCreate: (name: String, color: String, value: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var createNew by rememberSaveable { mutableStateOf(tags.isEmpty()) }
    var selectedId by rememberSaveable { mutableStateOf(tags.firstOrNull()?.id) }
    var name by rememberSaveable { mutableStateOf("") }
    var color by rememberSaveable { mutableStateOf(TAG_COLORS[4].first) }
    var value by rememberSaveable { mutableStateOf("") }
    val selected = tags.firstOrNull { it.id == selectedId }
    val canSave = if (createNew) name.isNotBlank() else selected != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_tag_add)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = !createNew,
                        onClick = { createNew = false },
                        enabled = tags.isNotEmpty(),
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                    ) { Text(stringResource(R.string.editor_tag_existing)) }
                    SegmentedButton(
                        selected = createNew,
                        onClick = { createNew = true },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                    ) { Text(stringResource(R.string.editor_tag_new)) }
                }
                if (createNew) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.tags_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TagColorPicker(selected = color, onSelect = { color = it })
                } else {
                    TagDropdown(tags, selected, onSelect = { selectedId = it.id })
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(stringResource(R.string.editor_tag_value)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    if (createNew) {
                        onCreate(
                            name.trim(),
                            color,
                            value,
                        )
                    } else {
                        selected?.let { onAdd(it, value) }
                    }
                },
            ) { Text(stringResource(R.string.editor_add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.monitors_cancel)) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TagDropdown(
    tags: List<Tag>,
    selected: Tag?,
    onSelect: (Tag) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected?.name.orEmpty(),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.editor_tag)) },
            leadingIcon = selected?.let { { ColorDot(it.color) } },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            tags.forEach { tag ->
                DropdownMenuItem(
                    text = { Text(tag.name) },
                    leadingIcon = { ColorDot(tag.color) },
                    onClick = {
                        expanded = false
                        onSelect(tag)
                    },
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun TagsSectionPreview() {
    KumaTheme(dynamicColor = false) {
        Surface {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.Start) {
                TagsSection(
                    tags = listOf(MonitorTag(1, "env", "#059669", "prod"), MonitorTag(2, "team", "#7C3AED")),
                    onAdd = {},
                    onRemove = {},
                    onManageTags = {},
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun AddTagDialogPreview() {
    KumaTheme(dynamicColor = false) {
        AddTagDialog(tags = listOf(Tag(1, "env", "#059669")), onAdd = {
            _,
            _,
            ->
        }, onCreate = { _, _, _ -> }, onDismiss = {})
    }
}
