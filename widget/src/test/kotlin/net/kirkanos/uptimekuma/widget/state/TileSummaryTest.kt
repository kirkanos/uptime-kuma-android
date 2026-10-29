package net.kirkanos.uptimekuma.widget.state

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TileSummaryTest {
    @Test
    fun `not configured is inactive`() {
        assertEquals(
            TileSummary(TileSubtitle.NotConfigured, active = false),
            TileSummary.of(MonitorsWidgetState.NotConfigured),
        )
    }

    @Test
    fun `all up is the only active state`() {
        assertEquals(
            TileSummary(TileSubtitle.AllUp, active = true),
            TileSummary.of(MonitorsWidgetState(OverallStatus.ALL_UP, up = 3)),
        )
    }

    @Test
    fun `down shows the down count`() {
        assertEquals(
            TileSummary(TileSubtitle.Down(2), active = false),
            TileSummary.of(MonitorsWidgetState(OverallStatus.DOWN, up = 5, down = 2, pending = 1)),
        )
    }

    @Test
    fun `pending shows the pending count`() {
        assertEquals(
            TileSummary(TileSubtitle.Pending(1), active = false),
            TileSummary.of(MonitorsWidgetState(OverallStatus.PENDING, up = 5, pending = 1)),
        )
    }

    @Test
    fun `no monitors`() {
        assertEquals(
            TileSummary(TileSubtitle.NoMonitors, active = false),
            TileSummary.of(MonitorsWidgetState(OverallStatus.NO_MONITORS)),
        )
    }
}
