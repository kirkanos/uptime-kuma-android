package net.kirkanos.uptimekuma.feature.monitors.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.kirkanos.uptimekuma.core.domain.editor.MonitorSpec
import net.kirkanos.uptimekuma.core.domain.editor.MonitorTypeSpec
import net.kirkanos.uptimekuma.core.domain.editor.TypeGroup
import net.kirkanos.uptimekuma.core.ui.theme.KumaTheme
import net.kirkanos.uptimekuma.feature.monitors.R

/** Types grouped like the web UI's type select (General, Specific, Database, Game server, Passive, Other). */
internal fun groupedTypes(spec: MonitorSpec = MonitorSpec.bundled): List<Pair<TypeGroup, List<MonitorTypeSpec>>> =
    TypeGroup.entries.mapNotNull { group ->
        spec.types
            .filter { it.group == group }
            .takeIf { it.isNotEmpty() }
            ?.let { group to it }
    }

@Composable
internal fun TypePickerEntry(
    onBack: () -> Unit,
    onTypeSelected: (String) -> Unit,
) {
    TypePickerScreen(onBack = onBack, onTypeSelected = onTypeSelected)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TypePickerScreen(
    onBack: () -> Unit,
    onTypeSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.editor_type_picker_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.monitors_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        TypeList(
            selected = null,
            onTypeSelected = onTypeSelected,
            modifier = Modifier.padding(padding).fillMaxSize(),
        )
    }
}

/** The grouped type list, shared by the picker screen and the editor's "change type" dialog. */
@Composable
internal fun TypeList(
    selected: String?,
    onTypeSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier, contentPadding = PaddingValues(bottom = 24.dp)) {
        groupedTypes().forEach { (group, types) ->
            item(key = "header-$group") {
                Text(
                    text = stringResource(group.labelRes()),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier =
                        Modifier
                            .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp)
                            .semantics { heading() },
                )
            }
            items(types, key = { it.id }) { type ->
                val isSelected = type.id == selected
                ListItem(
                    headlineContent = { Text(typeLabel(type)) },
                    supportingContent = typeHintRes(type.id)?.let { res -> { Text(stringResource(res)) } },
                    trailingContent =
                        if (isSelected) {
                            { Icon(Icons.Outlined.Check, contentDescription = null) }
                        } else {
                            null
                        },
                    modifier =
                        Modifier
                            .clickable { onTypeSelected(type.id) }
                            .semantics { this.selected = isSelected },
                )
            }
        }
    }
}

private fun TypeGroup.labelRes(): Int =
    when (this) {
        TypeGroup.GENERAL -> R.string.editor_group_general
        TypeGroup.SPECIFIC -> R.string.editor_group_specific
        TypeGroup.DATABASE -> R.string.editor_group_database
        TypeGroup.GAME_SERVER -> R.string.editor_group_game
        TypeGroup.PASSIVE -> R.string.editor_group_passive
        TypeGroup.OTHER -> R.string.editor_group_other
    }

private fun typeHintRes(type: String): Int? =
    when (type) {
        "push" -> R.string.editor_type_hint_push
        "group" -> R.string.editor_type_hint_group
        "manual" -> R.string.editor_type_hint_manual
        "system-service", "sip-options", "tailscale-ping" -> R.string.editor_type_hint_host_only
        else -> null
    }

@Preview(showBackground = true)
@Composable
private fun TypePickerPreview() {
    KumaTheme(dynamicColor = false) {
        TypePickerScreen(onBack = {}, onTypeSelected = {})
    }
}
