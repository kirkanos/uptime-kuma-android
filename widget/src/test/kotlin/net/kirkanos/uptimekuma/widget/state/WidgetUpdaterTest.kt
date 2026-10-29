package net.kirkanos.uptimekuma.widget.state

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.kirkanos.uptimekuma.core.model.MonitorStatus
import net.kirkanos.uptimekuma.core.testing.FakeMonitorRepository
import net.kirkanos.uptimekuma.core.testing.FakeSessionRepository
import net.kirkanos.uptimekuma.core.testing.SampleData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WidgetUpdaterTest {
    private class FakeSurfaces : WidgetSurfaces {
        var widgetRefreshes = 0
        var tileRequests = 0

        override suspend fun refreshWidgets() {
            widgetRefreshes++
        }

        override fun requestTileUpdate() {
            tileRequests++
        }
    }

    private val monitors = FakeMonitorRepository(listOf(overview(1, MonitorStatus.UP), overview(2, MonitorStatus.UP)))
    private val session = FakeSessionRepository(SampleData.serverConfig)
    private val holder = WidgetStateHolder(monitors, session)
    private val surfaces = FakeSurfaces()
    private var now = NOW

    private fun TestScope.updater() =
        WidgetUpdater(holder, surfaces, backgroundScope, { now }, WidgetUpdatePolicy(60_000))

    @Test
    fun `publishes the first state and redraws widget and tile`() =
        runTest {
            updater().start()
            runCurrent()
            assertEquals(OverallStatus.ALL_UP, holder.state.value?.overall)
            assertEquals(1, surfaces.widgetRefreshes)
            assertEquals(1, surfaces.tileRequests)
        }

    @Test
    fun `status change redraws immediately, ping-only change waits for the interval`() =
        runTest {
            updater().start()
            runCurrent()

            monitors.monitors.value = listOf(overview(1, MonitorStatus.UP, ping = 99.0), overview(2, MonitorStatus.UP))
            runCurrent()
            assertEquals(1, surfaces.widgetRefreshes)
            assertEquals(
                42.0,
                holder.state.value
                    ?.rows
                    ?.first()
                    ?.pingMs,
            )

            monitors.monitors.value = listOf(overview(1, MonitorStatus.DOWN), overview(2, MonitorStatus.UP))
            runCurrent()
            assertEquals(2, surfaces.widgetRefreshes)
            assertEquals(2, surfaces.tileRequests)
            assertEquals(OverallStatus.DOWN, holder.state.value?.overall)

            now += 60_000
            monitors.monitors.value = listOf(overview(1, MonitorStatus.DOWN, ping = 7.0), overview(2, MonitorStatus.UP))
            runCurrent()
            assertEquals(3, surfaces.widgetRefreshes)
            // Tile text unchanged ("1 down"): no tile request.
            assertEquals(2, surfaces.tileRequests)
        }

    @Test
    fun `logout switches to not configured`() =
        runTest {
            updater().start()
            runCurrent()
            session.serverConfig.value = null
            runCurrent()
            assertEquals(MonitorsWidgetState.NotConfigured, holder.state.value)
        }

    @Test
    fun `refreshNow always redraws`() =
        runTest {
            val updater = updater()
            updater.refreshNow()
            updater.refreshNow()
            assertEquals(2, surfaces.widgetRefreshes)
            assertEquals(2, surfaces.tileRequests)
        }

    @Test
    fun `listener refreshes through the updater`() =
        runTest {
            WidgetMonitorStateListener(updater()).onMonitorStateChanged()
            assertEquals(1, surfaces.widgetRefreshes)
        }

    @Test
    fun `holder loads from the cache when nothing was published`() =
        runTest {
            val state = holder.current()
            assertEquals(OverallStatus.ALL_UP, state.overall)
            assertEquals(state, holder.state.value)
            assertFalse(surfaces.widgetRefreshes > 0)
        }
}
