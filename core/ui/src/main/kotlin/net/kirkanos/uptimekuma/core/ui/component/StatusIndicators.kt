package net.kirkanos.uptimekuma.core.ui.component

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.kirkanos.uptimekuma.core.model.DisplayStatus
import net.kirkanos.uptimekuma.core.model.MonitorStatus
import net.kirkanos.uptimekuma.core.ui.R
import net.kirkanos.uptimekuma.core.ui.theme.KumaTheme
import net.kirkanos.uptimekuma.core.ui.theme.statusColors

/** Localized label of a [DisplayStatus] ("Up", "Down", ...). */
@StringRes
fun DisplayStatus.labelRes(): Int =
    when (this) {
        DisplayStatus.UP -> R.string.core_ui_status_up
        DisplayStatus.DOWN -> R.string.core_ui_status_down
        DisplayStatus.PENDING -> R.string.core_ui_status_pending
        DisplayStatus.MAINTENANCE -> R.string.core_ui_status_maintenance
        DisplayStatus.PAUSED -> R.string.core_ui_status_paused
        DisplayStatus.UNKNOWN -> R.string.core_ui_status_unknown
    }

/** Localized label of a heartbeat [MonitorStatus]. */
@StringRes
fun MonitorStatus.labelRes(): Int =
    when (this) {
        MonitorStatus.UP -> R.string.core_ui_status_up
        MonitorStatus.DOWN -> R.string.core_ui_status_down
        MonitorStatus.PENDING -> R.string.core_ui_status_pending
        MonitorStatus.MAINTENANCE -> R.string.core_ui_status_maintenance
    }

/**
 * Small filled circle in the status color. Announced by TalkBack as the status label unless
 * [contentDescription] is null *and* [announce] is false (use that when a row already says the status).
 */
@Composable
fun StatusDot(
    status: DisplayStatus,
    modifier: Modifier = Modifier,
    size: Dp = 12.dp,
    announce: Boolean = true,
) {
    val label = stringResource(status.labelRes())
    Box(
        modifier =
            modifier
                .size(size)
                .background(MaterialTheme.statusColors.forStatus(status), CircleShape)
                .then(
                    if (announce) {
                        Modifier.semantics { contentDescription = label }
                    } else {
                        Modifier.clearAndSetSemantics { }
                    },
                ),
    )
}

/** Pill with a status dot and the localized status label, tinted in the status color. */
@Composable
fun StatusChip(
    status: DisplayStatus,
    modifier: Modifier = Modifier,
) {
    val color = MaterialTheme.statusColors.forStatus(status)
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = color.copy(alpha = 0.16f).compositeOver(MaterialTheme.colorScheme.surface),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusDot(status = status, size = 10.dp, announce = false)
            Text(text = stringResource(status.labelRes()), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun StatusChipPreview() {
    KumaTheme(dynamicColor = false) {
        Surface {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DisplayStatus.entries.forEach { status ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        StatusDot(status)
                        StatusChip(status)
                    }
                }
            }
        }
    }
}
