package net.kirkanos.uptimekuma.core.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.kirkanos.uptimekuma.core.model.Heartbeat
import net.kirkanos.uptimekuma.core.model.MonitorStatus
import net.kirkanos.uptimekuma.core.ui.R
import net.kirkanos.uptimekuma.core.ui.theme.KumaTheme
import net.kirkanos.uptimekuma.core.ui.theme.statusColors

/** Per-status counts of the beats shown by a [HeartbeatBar]; used for its TalkBack description. */
data class BeatCounts(
    val total: Int,
    val up: Int,
    val down: Int,
    val other: Int,
) {
    companion object {
        fun of(beats: List<Heartbeat>): BeatCounts {
            var up = 0
            var down = 0
            beats.forEach {
                when (it.status) {
                    MonitorStatus.UP -> up++
                    MonitorStatus.DOWN -> down++
                    else -> Unit
                }
            }
            return BeatCounts(total = beats.size, up = up, down = down, other = beats.size - up - down)
        }
    }
}

/**
 * Uptime Kuma style heartbeat bar: one rounded bar per beat, newest on the right. Always lays out
 * [maxBars] slots across the available width; slots without a beat are drawn in a faint color.
 * Drawn in a single draw pass (no child layouts), cheap enough for every list row.
 *
 * @param beats heartbeats oldest first; only the last [maxBars] are shown.
 */
@Composable
fun HeartbeatBar(
    beats: List<Heartbeat>,
    maxBars: Int,
    modifier: Modifier = Modifier,
    height: Dp = 20.dp,
    gap: Dp = 2.dp,
) {
    val visible = remember(beats, maxBars) { if (beats.size > maxBars) beats.takeLast(maxBars) else beats }
    val colors = MaterialTheme.statusColors
    val counts = remember(visible) { BeatCounts.of(visible) }
    val description =
        if (counts.total == 0) {
            stringResource(R.string.core_ui_heartbeats_none)
        } else {
            pluralStringResource(
                R.plurals.core_ui_heartbeats_summary,
                counts.total,
                counts.total,
                counts.up,
                counts.down,
            )
        }

    Spacer(
        modifier =
            modifier
                .height(height)
                .clearAndSetSemantics { contentDescription = description }
                .drawBehind {
                    val slots = maxBars.coerceAtLeast(1)
                    val gapPx = gap.toPx()
                    val slotWidth = ((size.width - gapPx * (slots - 1)) / slots).coerceAtLeast(1f)
                    val radius = CornerRadius(minOf(slotWidth / 2f, 3.dp.toPx()))
                    val barSize = Size(slotWidth, size.height)
                    val firstBeatSlot = slots - visible.size
                    for (slot in 0 until slots) {
                        val beat = visible.getOrNull(slot - firstBeatSlot)
                        drawRoundRect(
                            color = beat?.let { colors.forBeat(it.status) } ?: colors.empty,
                            topLeft = Offset(slot * (slotWidth + gapPx), 0f),
                            size = barSize,
                            cornerRadius = radius,
                        )
                    }
                },
    )
}

@Preview(showBackground = true, widthDp = 360)
@Preview(showBackground = true, widthDp = 360, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun HeartbeatBarPreview() {
    val statuses =
        List(24) { MonitorStatus.UP } +
            listOf(MonitorStatus.PENDING, MonitorStatus.DOWN, MonitorStatus.DOWN, MonitorStatus.UP) +
            List(4) { MonitorStatus.MAINTENANCE }
    val beats = statuses.mapIndexed { i, s -> Heartbeat(monitorId = 1, status = s, timeMillis = i * 60_000L) }
    KumaTheme(dynamicColor = false) {
        Surface {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                HeartbeatBar(beats = beats, maxBars = 30, modifier = Modifier.fillMaxWidth())
                HeartbeatBar(beats = beats.take(10), maxBars = 30, modifier = Modifier.fillMaxWidth())
                HeartbeatBar(beats = beats, maxBars = 50, height = 36.dp, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
