package net.kirkanos.uptimekuma.feature.maintenance.list

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import net.kirkanos.uptimekuma.core.model.KumaError
import net.kirkanos.uptimekuma.core.model.KumaException
import net.kirkanos.uptimekuma.core.model.MaintenanceStatus
import net.kirkanos.uptimekuma.core.testing.FakeMaintenanceRepository
import net.kirkanos.uptimekuma.core.testing.MainDispatcherExtension
import net.kirkanos.uptimekuma.feature.maintenance.PreviewData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@OptIn(ExperimentalCoroutinesApi::class)
class MaintenanceListViewModelTest {
    @JvmField
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension()

    private val repository = FakeMaintenanceRepository(PreviewData.all)

    private fun TestScope.viewModel(): MaintenanceListViewModel {
        val vm = MaintenanceListViewModel(repository)
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()
        return vm
    }

    @Test
    fun `groups by status in display order and refreshes on start`() =
        runTest(mainDispatcher.testDispatcher) {
            val vm = viewModel()
            val groups = requireNotNull(vm.state.value.groups)
            assertEquals(
                listOf(
                    MaintenanceGroup.ACTIVE,
                    MaintenanceGroup.SCHEDULED,
                    MaintenanceGroup.PAUSED,
                    MaintenanceGroup.ENDED,
                ),
                groups.map { it.first },
            )
            assertEquals(listOf(6L, 1L), groups.first().second.map { it.id })
            assertEquals(1, repository.refreshCount)
            assertFalse(vm.state.value.loading)
        }

    @Test
    fun `loading until the list arrives, then empty state`() =
        runTest(mainDispatcher.testDispatcher) {
            repository.maintenances.value = null
            val vm = viewModel()
            assertTrue(vm.state.value.loading)
            repository.maintenances.value = emptyList()
            advanceUntilIdle()
            assertTrue(vm.state.value.isEmpty)
        }

    @Test
    fun `refresh failure without data shows the error, retry clears it`() =
        runTest(mainDispatcher.testDispatcher) {
            repository.maintenances.value = null
            repository.error = KumaException(KumaError(KumaError.Kind.UNAUTHORIZED))
            val vm = viewModel()
            assertEquals(
                KumaError.Kind.UNAUTHORIZED,
                vm.state.value.loadError
                    ?.kind,
            )
            assertFalse(vm.state.value.loading)
            repository.error = null
            vm.refresh()
            advanceUntilIdle()
            assertNull(vm.state.value.loadError)
            assertEquals(1, repository.refreshCount)
        }

    @Test
    fun `pause, resume and delete report results`() =
        runTest(mainDispatcher.testDispatcher) {
            val vm = viewModel()
            vm.state.test {
                skipItems(1)
                vm.pause(2)
                assertEquals(setOf(2L), awaitItem().busyIds)
                val done = expectMostRecentItem()
                assertEquals(ActionResult.Done(MaintenanceAction.PAUSE), done.actionResult)
                assertTrue(done.busyIds.isEmpty())
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals(listOf(2L), repository.pausedIds)
            assertEquals(
                MaintenanceStatus.INACTIVE,
                repository.maintenances.value
                    ?.first { it.id == 2L }
                    ?.status,
            )

            vm.consumeActionResult()
            vm.resume(4)
            vm.delete(5)
            advanceUntilIdle()
            assertEquals(listOf(4L), repository.resumedIds)
            assertEquals(listOf(5L), repository.deletedIds)
            assertTrue(
                vm.state.value.groups
                    .orEmpty()
                    .flatMap { it.second }
                    .none { it.id == 5L },
            )
        }

    @Test
    fun `failed action surfaces the error`() =
        runTest(mainDispatcher.testDispatcher) {
            val vm = viewModel()
            repository.error = KumaException(KumaError(KumaError.Kind.SERVER_REJECTED, "Permission denied."))
            vm.delete(1)
            advanceUntilIdle()
            assertEquals(
                ActionResult.Failed(KumaError(KumaError.Kind.SERVER_REJECTED, "Permission denied.")),
                vm.state.value.actionResult,
            )
            vm.consumeActionResult()
            advanceUntilIdle()
            assertNull(vm.state.value.actionResult)
        }
}
