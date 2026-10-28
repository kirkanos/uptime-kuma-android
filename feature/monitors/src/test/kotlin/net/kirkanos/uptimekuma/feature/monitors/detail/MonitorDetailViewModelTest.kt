package net.kirkanos.uptimekuma.feature.monitors.detail

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import net.kirkanos.uptimekuma.core.model.KumaError
import net.kirkanos.uptimekuma.core.model.KumaException
import net.kirkanos.uptimekuma.core.testing.FakeMonitorRepository
import net.kirkanos.uptimekuma.core.testing.FakeSessionRepository
import net.kirkanos.uptimekuma.core.testing.MainDispatcherExtension
import net.kirkanos.uptimekuma.core.testing.SampleData
import net.kirkanos.uptimekuma.feature.monitors.MonitorDetailRoute
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@OptIn(ExperimentalCoroutinesApi::class)
class MonitorDetailViewModelTest {
    @JvmField
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension()

    private val now = 1_761_700_000_000L
    private val monitors = FakeMonitorRepository.withSampleData(now)
    private val session = FakeSessionRepository(SampleData.serverConfig)

    private fun TestScope.viewModel(id: Long = 1): MonitorDetailViewModel {
        val vm =
            MonitorDetailViewModel(
                SavedStateHandle(mapOf(MonitorDetailRoute.ARG_MONITOR_ID to id)),
                monitors,
                session,
            )
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()
        return vm
    }

    @Test
    fun `loads monitor, 24h chart and first page of events`() =
        runTest(mainDispatcher.testDispatcher) {
            val vm = viewModel()
            val state = vm.state.value
            assertFalse(state.loading)
            assertEquals("Website", state.overview?.monitor?.name)
            assertEquals(ChartPeriod.H24, state.chart.period)
            assertFalse(state.chart.loading)
            assertTrue(state.chart.points.isNotEmpty())
            assertEquals(listOf(1L to 24), monitors.chartRequests)
            assertEquals(Triple(1L, 0, MonitorDetailViewModel.PAGE_SIZE), monitors.eventRequests.single())
            assertTrue(state.events.items.all { it.monitorId == 1L })
            assertEquals("https://uptime.example.org/dashboard/1", state.webUrl)
        }

    @Test
    fun `selecting a period reloads the chart`() =
        runTest(mainDispatcher.testDispatcher) {
            val vm = viewModel()
            vm.selectPeriod(ChartPeriod.W1)
            advanceUntilIdle()
            assertEquals(ChartPeriod.W1, vm.state.value.chart.period)
            assertEquals(1L to 168, monitors.chartRequests.last())
            vm.selectPeriod(ChartPeriod.W1)
            advanceUntilIdle()
            assertEquals(2, monitors.chartRequests.size)
        }

    @Test
    fun `chart error can be retried`() =
        runTest(mainDispatcher.testDispatcher) {
            monitors.error = KumaException(KumaError(KumaError.Kind.TIMEOUT))
            val vm = viewModel()
            assertEquals(
                KumaError.Kind.TIMEOUT,
                vm.state.value.chart.error
                    ?.kind,
            )
            monitors.error = null
            vm.retryChart()
            advanceUntilIdle()
            assertNull(vm.state.value.chart.error)
            assertTrue(
                vm.state.value.chart.points
                    .isNotEmpty(),
            )
        }

    @Test
    fun `events are paged until a short page arrives`() =
        runTest(mainDispatcher.testDispatcher) {
            monitors.importantEvents = SampleData.importantEvents(now).filter { it.monitorId == 1L }.take(5) +
                List(40) { SampleData.importantEvents(now).first().copy(monitorId = 1, timeMillis = it.toLong()) }
            val vm = viewModel()
            assertEquals(25, vm.state.value.events.items.size)
            assertFalse(vm.state.value.events.endReached)

            vm.loadMoreEvents()
            advanceUntilIdle()
            assertEquals(45, vm.state.value.events.items.size)
            assertTrue(vm.state.value.events.endReached)

            vm.loadMoreEvents()
            advanceUntilIdle()
            assertEquals(2, monitors.eventRequests.size)
            assertEquals(25, monitors.eventRequests.last().second)
        }

    @Test
    fun `pause and resume update the monitor`() =
        runTest(mainDispatcher.testDispatcher) {
            val vm = viewModel()
            vm.pause()
            advanceUntilIdle()
            assertEquals(listOf(1L), monitors.pausedIds)
            assertEquals(
                false,
                vm.state.value.overview
                    ?.monitor
                    ?.active,
            )
            assertNull(vm.state.value.actionInProgress)

            vm.resume()
            advanceUntilIdle()
            assertEquals(
                true,
                vm.state.value.overview
                    ?.monitor
                    ?.active,
            )
        }

    @Test
    fun `action failure is reported and can be dismissed`() =
        runTest(mainDispatcher.testDispatcher) {
            val vm = viewModel()
            monitors.error = KumaException(KumaError(KumaError.Kind.SERVER_REJECTED, "nope"))
            vm.pause()
            advanceUntilIdle()
            assertEquals(
                KumaError.Kind.SERVER_REJECTED,
                vm.state.value.actionError
                    ?.kind,
            )
            assertEquals(
                true,
                vm.state.value.overview
                    ?.monitor
                    ?.active,
            )
            vm.dismissActionError()
            advanceUntilIdle()
            assertNull(vm.state.value.actionError)
        }

    @Test
    fun `delete marks the screen as deleted`() =
        runTest(mainDispatcher.testDispatcher) {
            val vm = viewModel()
            vm.delete()
            advanceUntilIdle()
            assertTrue(vm.state.value.deleted)
            assertFalse(vm.state.value.notFound)
            assertEquals(listOf(1L), monitors.deletedIds)
        }

    @Test
    fun `unknown monitor is reported as not found`() =
        runTest(mainDispatcher.testDispatcher) {
            val vm = viewModel(id = 999)
            assertTrue(vm.state.value.notFound)
        }
}
