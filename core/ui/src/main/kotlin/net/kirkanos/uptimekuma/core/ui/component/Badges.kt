package net.kirkanos.uptimekuma.core.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.kirkanos.uptimekuma.core.model.MonitorTag
import net.kirkanos.uptimekuma.core.ui.R
import net.kirkanos.uptimekuma.core.ui.format.currentLocale
import net.kirkanos.uptimekuma.core.ui.format.formatUptimePercent
import net.kirkanos.uptimekuma.core.ui.theme.KumaTheme
import net.kirkanos.uptimekuma.core.ui.theme.statusColors

private const val GOOD_UPTIME = 0.99
private const val FAIR_UPTIME = 0.95
private const val HEX_RGB_LENGTH = 6
private const val HEX_ARGB_LENGTH = 8
private const val HEX_RADIX = 16
private const val OPAQUE = 0xFF000000L
private const val LIGHT_LUMINANCE = 0.5f

/**
 * Uptime as a compact tinted percentage pill. Green from 99 %, amber from 95 %, red below;
 * shows a dash when [fraction] is unknown.
 */
@Composable
fun UptimeBadge(
    fraction: Double?,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.statusColors
    val color =
        when {
            fraction == null -> colors.unknown
            fraction >= GOOD_UPTIME -> colors.up
            fraction >= FAIR_UPTIME -> colors.pending
            else -> colors.down
        }
    val locale = currentLocale()
    val text = fraction?.let { remember(it, locale) { formatUptimePercent(it, locale) } } ?: "–"
    val description =
        if (fraction == null) {
            stringResource(R.string.core_ui_uptime_unknown)
        } else {
            stringResource(R.string.core_ui_uptime_description, text)
        }
    Surface(
        modifier = modifier.semantics { contentDescription = description },
        shape = CircleShape,
        color = color.copy(alpha = 0.18f).compositeOver(MaterialTheme.colorScheme.surface),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
        )
    }
}

/** Parses Kuma's `#RRGGBB` / `#AARRGGBB` tag colors; returns null for anything else. */
fun parseHexColor(hex: String): Color? {
    val digits = hex.trim().removePrefix("#")
    val value = digits.toLongOrNull(HEX_RADIX) ?: return null
    return when (digits.length) {
        HEX_RGB_LENGTH -> Color(OPAQUE or value)
        HEX_ARGB_LENGTH -> Color(value)
        else -> null
    }
}

/** Kuma tag as a small colored pill: `name` or `name: value`. */
@Composable
fun TagChip(
    tag: MonitorTag,
    modifier: Modifier = Modifier,
) {
    val background = remember(tag.color) { parseHexColor(tag.color) } ?: MaterialTheme.colorScheme.secondary
    val content = if (background.luminance() > LIGHT_LUMINANCE) Color.Black else Color.White
    val label = if (tag.value.isBlank()) tag.name else "${tag.name}: ${tag.value}"
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = background,
        contentColor = content,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun BadgesPreview() {
    KumaTheme(dynamicColor = false) {
        Surface {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    UptimeBadge(1.0)
                    UptimeBadge(0.9987)
                    UptimeBadge(0.962)
                    UptimeBadge(0.5)
                    UptimeBadge(null)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TagChip(MonitorTag(1, "env", "#059669", "prod"))
                    TagChip(MonitorTag(2, "team", "#7C3AED", "android"))
                    TagChip(MonitorTag(3, "critical", "#FACC15"))
                }
            }
        }
    }
}
