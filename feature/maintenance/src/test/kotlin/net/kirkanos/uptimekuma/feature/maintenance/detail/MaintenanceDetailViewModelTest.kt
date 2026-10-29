package net.kirkanos.uptimekuma.feature.maintenance.detail

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import net.kirkanos.uptimekuma.core.model.DisplayStatus
import net.kirkanos.uptimekuma.core.model.KumaError
import net.kirkanos.uptimekuma.core.model.KumaException
import net.kirkanos.uptimekuma.core.model.MaintenanceStatus
import net.kirkanos.uptimekuma.core.testing.FakeMaintenanceRepository
import net.kirkanos.uptimekuma.core.testing.FakeMonitorRepository
import net.kirkanos.uptimekuma.core.testing.MainDispatcherExtension
import net.kirkanos.uptimekuma.feature.maintenance.MaintenanceDetailRoute
import net.kirkanos.uptimekuma.feature.maintenance.PreviewData
import net.kirkanos.uptimekuma.feature.maintenance.list.ActionResult
import net.kirkanos.uptimekuma.feature.maintenance.list.MaintenanceAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@OptIn(ExperimentalCoroutinesApi::class)
class MaintenanceDetailViewModelTest {
    @JvmField
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension()

    private val repository =
        FakeMaintenanceRepository(PreviewData.all, PreviewData.statusPages).apply {
            links[2] = listOf(1L, 404L) to listOf(1L)
        }
    private val monitors = FakeMonitorRepository.withSampleData()

    private fun TestScope.viewModel(
        id: Long = 2,
        load: Boolean = true,
    ): MaintenanceDetailViewModel {
        val vm =
            MaintenanceDetailViewModel(
                SavedStateHandle(mapOf(MaintenanceDetailRoute.ARG_ID to id)),
                repository,
                monitors,
            )
        backgroundScope.launch { vm.state.collect {} }
        if (load) vm.load()
        advanceUntilIdle()
        return vm
    }

    @Test
    fun `shows the live maintenance with affected monitors and status pages`() =
        runTest(mainDispatcher.testDispatcher) {
            val vm = viewModel()
            val state = vm.state.value
            assertFalse(state.loading)
            assertEquals("Weekly patch window", state.maintenance?.title)
            assertEquals(listOf(2L), repository.getRequests)
            val website = state.monitors.first()
            assertEquals(1L, website.id)
            assertEquals("Website", website.name)
            assertTrue(website.status != null)
            // Unknown monitor ids are still listed, without a name.
            assertEquals(AffectedMonitor(404, null, null), state.monitors.last())
            assertEquals(listOf("Public status"), state.statusPages.map { it.title })
        }

    @Test
    fun `live list updates win over the fetched detail`() =
        runTest(mainDispatcher.testDispatcher) {
            val vm = viewModel()
            repository.maintenances.value =
                repository.maintenances.value?.map {
                    if (it.id == 2L) it.copy(status = MaintenanceStatus.UNDER_MAINTENANCE) else it
                }
            advanceUntilIdle()
            assertEquals(
                MaintenanceStatus.UNDER_MAINTENANCE,
                vm.state.value.maintenance
                    ?.status,
            )
        }

    @Test
    fun `link loading failure keeps showing the maintenance from the list`() =
        runTest(mainDispatcher.testDispatcher) {
            repository.error = KumaException(KumaError(KumaError.Kind.TIMEOUT))
            val vm = viewModel()
            assertEquals(
                "Weekly patch window",
                vm.state.value.maintenance
                    ?.title,
            )
            assertEquals(
                KumaError.Kind.TIMEOUT,
                vm.state.value.loadError
                    ?.kind,
            )
            repository.error = null
            vm.load()
            advanceUntilIdle()
            assertNull(vm.state.value.loadError)
            assertEquals(2, vm.state.value.monitors.size)
        }

    @Test
    fun `unknown id ends as not found`() =
        runTest(mainDispatcher.testDispatcher) {
            val vm = viewModel(id = 999)
            assertNull(vm.state.value.maintenance)
            assertEquals(
                KumaError.Kind.SERVER_REJECTED,
                vm.state.value.loadError
                    ?.kind,
            )
        }

    @Test
    fun `pause, resume and delete`() =
        runTest(mainDispatcher.testDispatcher) {
            val vm = viewModel()
            vm.pause()
            advanceUntilIdle()
            assertEquals(listOf(2L), repository.pausedIds)
            assertFalse(
                vm.state.value.maintenance!!
                    .active,
            )
            assertEquals(ActionResult.Done(MaintenanceAction.PAUSE), vm.state.value.actionResult)
            vm.resume()
            advanceUntilIdle()
            assertTrue(
                vm.state.value.maintenance!!
                    .active,
            )
            vm.delete()
            advanceUntilIdle()
            assertTrue(vm.state.value.deleted)
            assertEquals(listOf(2L), repository.deletedIds)
            assertFalse(vm.state.value.notFound)
        }

    @Test
    fun `monitor status comes from the monitor repository`() =
        runTest(mainDispatcher.testDispatcher) {
            val vm = viewModel()
            val expected =
                monitors.monitors.value
                    .first { it.monitor.id == 1L }
                    .displayStatus
            assertEquals(
                expected,
                vm.state.value.monitors
                    .first()
                    .status,
            )
            assertTrue(expected in DisplayStatus.entries)
        }
}
