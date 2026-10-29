package net.kirkanos.uptimekuma.widget.state

import net.kirkanos.uptimekuma.core.model.DisplayStatus
import net.kirkanos.uptimekuma.core.model.MonitorStatus
import net.kirkanos.uptimekuma.core.testing.SampleData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WidgetStateMapperTest {
    @Test
    fun `not configured ignores the cache`() {
        val state = WidgetStateMapper.map(configured = false, monitors = listOf(overview(1, MonitorStatus.UP)))
        assertEquals(MonitorsWidgetState.NotConfigured, state)
        assertEquals(OverallStatus.NOT_CONFIGURED, state.overall)
    }

    @Test
    fun `empty cache means no monitors`() {
        val state = WidgetStateMapper.map(configured = true, monitors = emptyList())
        assertEquals(OverallStatus.NO_MONITORS, state.overall)
        assertTrue(state.rows.isEmpty())
        assertNull(state.updatedAtMillis)
    }

    @Test
    fun `only paused monitors count as no monitors`() {
        val state = WidgetStateMapper.map(true, listOf(overview(1, MonitorStatus.UP, active = false)))
        assertEquals(OverallStatus.NO_MONITORS, state.overall)
        assertEquals(1, state.paused)
        assertTrue(state.rows.isEmpty())
    }

    @Test
    fun `all up with maintenance is still all up`() {
        val state =
            WidgetStateMapper.map(
                true,
                listOf(overview(1, MonitorStatus.UP), overview(2, MonitorStatus.UP, inMaintenance = true)),
            )
        assertEquals(OverallStatus.ALL_UP, state.overall)
        assertEquals(1, state.up)
        assertEquals(1, state.maintenance)
        assertEquals(2, state.active)
    }

    @Test
    fun `down wins over pending and problems are listed first`() {
        val monitors =
            listOf(
                overview(1, MonitorStatus.UP, name = "A"),
                overview(2, MonitorStatus.PENDING, name = "B"),
                overview(3, MonitorStatus.UP, name = "C"),
                overview(4, MonitorStatus.MAINTENANCE, name = "D"),
                overview(5, MonitorStatus.DOWN, name = "E"),
                overview(6, MonitorStatus.DOWN, name = "F"),
                overview(7, MonitorStatus.UP, name = "G", active = false),
                overview(8, null, name = "H"),
            )
        val state = WidgetStateMapper.map(true, monitors)
        assertEquals(OverallStatus.DOWN, state.overall)
        assertEquals(2, state.down)
        assertEquals(1, state.pending)
        assertEquals(1, state.maintenance)
        assertEquals(2, state.up)
        assertEquals(1, state.paused)
        assertEquals(listOf(5L, 6L, 2L, 4L, 1L, 3L), state.rows.map { it.id })
        assertEquals(
            listOf(
                DisplayStatus.DOWN,
                DisplayStatus.DOWN,
                DisplayStatus.PENDING,
                DisplayStatus.MAINTENANCE,
                DisplayStatus.UP,
                DisplayStatus.UP,
            ),
            state.rows.map { it.status },
        )
    }

    @Test
    fun `pending without down is pending`() {
        val state =
            WidgetStateMapper.map(
                true,
                listOf(overview(1, MonitorStatus.UP), overview(2, MonitorStatus.PENDING)),
            )
        assertEquals(OverallStatus.PENDING, state.overall)
    }

    @Test
    fun `groups are not counted and rows carry last beat and ping`() {
        val state =
            WidgetStateMapper.map(
                true,
                listOf(
                    overview(1, MonitorStatus.DOWN, type = "group"),
                    overview(2, MonitorStatus.UP, beatTime = NOW - 5_000, ping = 120.0),
                    overview(3, MonitorStatus.UP, beatTime = NOW - 1_000, ping = null),
                ),
            )
        assertEquals(OverallStatus.ALL_UP, state.overall)
        assertEquals(listOf(2L, 3L), state.rows.map { it.id })
        assertEquals(120.0, state.rows[0].pingMs)
        assertEquals(NOW - 5_000, state.rows[0].lastBeatMillis)
        assertEquals(NOW - 1_000, state.updatedAtMillis)
    }

    @Test
    fun `sample data maps to a down state`() {
        val state = WidgetStateMapper.map(true, SampleData.monitors(NOW))
        assertTrue(state.down > 0)
        assertEquals(OverallStatus.DOWN, state.overall)
        assertEquals(DisplayStatus.DOWN, state.rows.first().status)
        assertFalse(state.rows.any { it.status == DisplayStatus.PAUSED })
    }

    @Test
    fun `significant part ignores beat times and pings`() {
        val a = WidgetStateMapper.map(true, listOf(overview(1, MonitorStatus.UP, beatTime = NOW, ping = 10.0)))
        val b = WidgetStateMapper.map(true, listOf(overview(1, MonitorStatus.UP, beatTime = NOW + 60_000, ping = 99.0)))
        val c = WidgetStateMapper.map(true, listOf(overview(1, MonitorStatus.DOWN, beatTime = NOW + 60_000)))
        assertTrue(a != b)
        assertEquals(a.significant(), b.significant())
        assertTrue(a.significant() != c.significant())
    }
}
