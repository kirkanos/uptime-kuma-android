package net.kirkanos.uptimekuma.feature.monitors.tags

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import net.kirkanos.uptimekuma.core.ui.component.parseHexColor
import net.kirkanos.uptimekuma.feature.monitors.R

/** Kuma's preset tag colors (`src/components/TagEditDialog.vue`), with their names' string resources. */
internal val TAG_COLORS: List<Pair<String, Int>> =
    listOf(
        "#4B5563" to R.string.tags_color_grey,
        "#DC2626" to R.string.tags_color_red,
        "#D97706" to R.string.tags_color_orange,
        "#059669" to R.string.tags_color_green,
        "#2563EB" to R.string.tags_color_blue,
        "#4F46E5" to R.string.tags_color_indigo,
        "#7C3AED" to R.string.tags_color_purple,
        "#DB2777" to R.string.tags_color_pink,
    )

/** Round swatches of [TAG_COLORS]; the selected one carries a check mark. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TagColorPicker(
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TAG_COLORS.forEach { (hex, nameRes) ->
            val isSelected = hex.equals(selected, ignoreCase = true)
            val name = stringResource(nameRes)
            Box(
                modifier =
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(parseHexColor(hex) ?: Color.Gray)
                        .border(
                            width = if (isSelected) 3.dp else 0.dp,
                            color = MaterialTheme.colorScheme.onSurface,
                            shape = CircleShape,
                        ).selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(hex) })
                        .semantics { contentDescription = name },
                contentAlignment = Alignment.Center,
            ) {
                if (isSelected) Icon(Icons.Outlined.Check, contentDescription = null, tint = Color.White)
            }
        }
    }
}
