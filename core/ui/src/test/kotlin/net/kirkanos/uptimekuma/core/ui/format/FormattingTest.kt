package net.kirkanos.uptimekuma.core.ui.format

import androidx.compose.ui.graphics.Color
import net.kirkanos.uptimekuma.core.model.ChartPoint
import net.kirkanos.uptimekuma.core.model.Heartbeat
import net.kirkanos.uptimekuma.core.model.MonitorStatus
import net.kirkanos.uptimekuma.core.ui.component.BeatCounts
import net.kirkanos.uptimekuma.core.ui.component.PingStats
import net.kirkanos.uptimekuma.core.ui.component.niceAxisMax
import net.kirkanos.uptimekuma.core.ui.component.parseHexColor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.Locale

class FormattingTest {
    private val now = 1_761_700_000_000L

    @Test
    fun `relative time buckets`() {
        assertEquals(RelativeTime.JustNow, RelativeTime.between(now - 10_000, now))
        assertEquals(RelativeTime.JustNow, RelativeTime.between(now + 5_000, now))
        assertEquals(RelativeTime.Minutes(1), RelativeTime.between(now - 50_000, now))
        assertEquals(RelativeTime.Minutes(3), RelativeTime.between(now - 3 * 60_000 - 20_000, now))
        assertEquals(RelativeTime.Hours(2), RelativeTime.between(now - 2 * 3_600_000 - 1, now))
        assertEquals(RelativeTime.Days(3), RelativeTime.between(now - 3 * 86_400_000L, now))
    }

    @Test
    fun `uptime percent is localized and trims perfect uptime`() {
        assertEquals("99.95%", formatUptimePercent(0.9995, Locale.US))
        assertEquals("100%", formatUptimePercent(0.99999, Locale.US))
        assertEquals("99,5 %", formatUptimePercent(0.995, Locale.GERMANY).replace(' ', ' '))
    }

    @Test
    fun `axis max rounds to nice numbers`() {
        assertEquals(10.0, niceAxisMax(1.0))
        assertEquals(200.0, niceAxisMax(123.0))
        assertEquals(500.0, niceAxisMax(400.0))
        assertEquals(1000.0, niceAxisMax(600.0))
    }

    @Test
    fun `ping stats ignore missing pings`() {
        val points =
            listOf(
                ChartPoint(1, 10.0),
                ChartPoint(2, null, downCount = 1),
                ChartPoint(3, 30.0),
            )
        assertEquals(PingStats(10.0, 20.0, 30.0), PingStats.of(points))
        assertNull(PingStats.of(listOf(ChartPoint(1, null))))
    }

    @Test
    fun `beat counts`() {
        val beats =
            listOf(MonitorStatus.UP, MonitorStatus.DOWN, MonitorStatus.UP, MonitorStatus.PENDING)
                .map { Heartbeat(1, it, 0) }
        assertEquals(BeatCounts(total = 4, up = 2, down = 1, other = 1), BeatCounts.of(beats))
    }

    @Test
    fun `hex colors`() {
        assertEquals(Color(0xFF059669), parseHexColor("#059669"))
        assertEquals(Color(0x80059669), parseHexColor("80059669"))
        assertNull(parseHexColor("blue"))
        assertNull(parseHexColor("#12345"))
    }
}
