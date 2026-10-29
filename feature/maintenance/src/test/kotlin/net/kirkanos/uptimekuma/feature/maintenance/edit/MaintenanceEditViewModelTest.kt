package net.kirkanos.uptimekuma.feature.maintenance.edit

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import net.kirkanos.uptimekuma.core.model.KumaError
import net.kirkanos.uptimekuma.core.model.KumaException
import net.kirkanos.uptimekuma.core.model.MaintenanceMonthDay
import net.kirkanos.uptimekuma.core.model.MaintenanceStrategy
import net.kirkanos.uptimekuma.core.testing.FakeMaintenanceRepository
import net.kirkanos.uptimekuma.core.testing.FakeMonitorRepository
import net.kirkanos.uptimekuma.core.testing.MainDispatcherExtension
import net.kirkanos.uptimekuma.feature.maintenance.MaintenanceEditRoute
import net.kirkanos.uptimekuma.feature.maintenance.PreviewData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime

@OptIn(ExperimentalCoroutinesApi::class)
class MaintenanceEditViewModelTest {
    @JvmField
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension()

    private val repository =
        FakeMaintenanceRepository(PreviewData.all, PreviewData.statusPages).apply {
            links[2] = listOf(1L) to listOf(1L, 2L)
            links[4] = listOf(1L, 2L) to listOf(2L)
        }
    private val monitors = FakeMonitorRepository.withSampleData()

    private fun TestScope.viewModel(
        id: Long = MaintenanceEditRoute.NEW,
        clone: Boolean = false,
    ): MaintenanceEditViewModel {
        val handle =
            SavedStateHandle(mapOf(MaintenanceEditRoute.ARG_ID to id, MaintenanceEditRoute.ARG_CLONE to clone))
        val vm = MaintenanceEditViewModel(handle, repository, monitors)
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()
        return vm
    }

    @Test
    fun `new maintenance starts with web UI defaults and options`() =
        runTest(mainDispatcher.testDispatcher) {
            val vm = viewModel()
            val state = vm.state.value
            assertFalse(state.loading)
            assertTrue(state.isNew)
            val draft = state.draft
            assertNull(draft.id)
            assertEquals(MaintenanceStrategy.SINGLE, draft.strategy)
            assertEquals(Duration.ofHours(1), Duration.between(draft.startDate, draft.endDate))
            assertTrue(Duration.between(draft.startDate, LocalDateTime.now()).abs() < Duration.ofMinutes(2))
            assertEquals(monitors.monitors.value.size, state.monitors.size)
            assertEquals(PreviewData.statusPages, state.statusPages)
            assertEquals("Europe/Berlin", state.serverTimezone)
            assertTrue(repository.getRequests.isEmpty())
        }

    @Test
    fun `saving an invalid form only shows errors`() =
        runTest(mainDispatcher.testDispatcher) {
            val vm = viewModel()
            assertTrue(
                vm.state.value.visibleErrors
                    .isEmpty(),
            )
            vm.save()
            advanceUntilIdle()
            assertEquals(
                setOf(MaintenanceFieldError.TITLE_REQUIRED, MaintenanceFieldError.TARGETS_REQUIRED),
                vm.state.value.visibleErrors,
            )
            assertTrue(repository.savedDrafts.isEmpty())
        }

    @Test
    fun `new single window is saved with monitors and status pages`() =
        runTest(mainDispatcher.testDispatcher) {
            val vm = viewModel()
            vm.edit(DraftChange.Title("Upgrade"))
            vm.edit(DraftChange.Monitors(setOf(1, 2)))
            vm.edit(DraftChange.ToggleStatusPage(2))
            vm.edit(DraftChange.StartDate(LocalDateTime.of(2026, 11, 2, 22, 0)))
            vm.edit(DraftChange.QuickDuration(240))
            vm.save()
            advanceUntilIdle()
            val saved = repository.savedDrafts.single()
            assertEquals("Upgrade", saved.title)
            assertEquals(LocalDateTime.of(2026, 11, 3, 2, 0), saved.endDate)
            assertEquals(setOf(1L, 2L), saved.monitorIds)
            assertEquals(setOf(2L), saved.statusPageIds)
            assertTrue(vm.state.value.done)
        }

    @Test
    fun `status pages only asks for confirmation first`() =
        runTest(mainDispatcher.testDispatcher) {
            val vm = viewModel()
            vm.edit(DraftChange.Title("Only pages"))
            vm.edit(DraftChange.AllStatusPages(true))
            vm.save()
            advanceUntilIdle()
            assertTrue(vm.state.value.confirmNoMonitors)
            assertTrue(repository.savedDrafts.isEmpty())
            vm.dismissConfirm()
            advanceUntilIdle()
            assertFalse(vm.state.value.confirmNoMonitors)
            vm.save(confirmed = true)
            advanceUntilIdle()
            assertEquals(setOf(1L, 2L), repository.savedDrafts.single().statusPageIds)
            assertTrue(vm.state.value.done)
        }

    @Test
    fun `strategy specific fields`() =
        runTest(mainDispatcher.testDispatcher) {
            val vm = viewModel()
            vm.edit(DraftChange.Title("x"))
            vm.edit(DraftChange.Monitors(setOf(1)))
            vm.edit(DraftChange.Strategy(MaintenanceStrategy.RECURRING_DAY_OF_MONTH))
            vm.edit(DraftChange.ToggleMonthDay(MaintenanceMonthDay.Day(15)))
            vm.edit(DraftChange.ToggleMonthDay(MaintenanceMonthDay.LAST))
            vm.edit(DraftChange.ToggleMonthDay(MaintenanceMonthDay.Day(15)))
            vm.edit(DraftChange.StartTime(LocalTime.of(23, 0)))
            vm.edit(DraftChange.EndTime(LocalTime.of(1, 0)))
            vm.edit(DraftChange.Timezone("UTC"))
            vm.edit(DraftChange.EndDate(null))
            vm.edit(DraftChange.Interval("12a"))
            advanceUntilIdle()
            val draft = vm.state.value.draft
            assertEquals(setOf(MaintenanceMonthDay.LAST), draft.daysOfMonth)
            assertEquals(LocalTime.of(23, 0), draft.startTime)
            assertEquals("UTC", draft.timezoneOption)
            assertEquals("12a", vm.state.value.intervalText)
            assertEquals(0, draft.intervalDay)
            assertTrue(
                vm.state.value.errors
                    .isEmpty(),
            )

            vm.edit(DraftChange.Strategy(MaintenanceStrategy.CRON))
            vm.edit(DraftChange.Cron("bad"))
            vm.edit(DraftChange.Duration(""))
            advanceUntilIdle()
            assertEquals(
                setOf(MaintenanceFieldError.CRON_INVALID, MaintenanceFieldError.DURATION_INVALID),
                vm.state.value.errors,
            )
        }

    @Test
    fun `editing loads the maintenance with its links and saves as edit`() =
        runTest(mainDispatcher.testDispatcher) {
            val vm = viewModel(id = 2)
            val state = vm.state.value
            assertFalse(state.isNew)
            assertEquals(2L, state.draft.id)
            assertEquals(setOf(1L), state.draft.monitorIds)
            // Every existing page is linked -> "All status pages" like the web UI.
            assertTrue(state.allStatusPages)
            assertEquals(setOf(1, 3, 5), state.draft.weekdays)
            vm.edit(DraftChange.ToggleWeekday(3))
            vm.save()
            advanceUntilIdle()
            val saved = repository.savedDrafts.single()
            assertEquals(2L, saved.id)
            assertEquals(setOf(1, 5), saved.weekdays)
            assertTrue(vm.state.value.done)
        }

    @Test
    fun `clone loads the source without id and asks for a clone title`() =
        runTest(mainDispatcher.testDispatcher) {
            val vm = viewModel(id = 4, clone = true)
            val state = vm.state.value
            assertTrue(state.isClone)
            assertTrue(state.isNew)
            assertNull(state.draft.id)
            assertFalse(state.allStatusPages)
            assertEquals(setOf(2L), state.draft.statusPageIds)
            assertEquals("Nightly reindex", state.pendingCloneTitle)
            vm.applyCloneTitle("Clone of Nightly reindex")
            advanceUntilIdle()
            assertNull(vm.state.value.pendingCloneTitle)
            assertEquals("Clone of Nightly reindex", vm.state.value.draft.title)
            vm.save()
            advanceUntilIdle()
            assertNull(repository.savedDrafts.single().id)
        }

    @Test
    fun `load failure can be retried`() =
        runTest(mainDispatcher.testDispatcher) {
            repository.error = KumaException(KumaError(KumaError.Kind.NETWORK))
            val vm = viewModel(id = 2)
            assertEquals(
                KumaError.Kind.NETWORK,
                vm.state.value.loadError
                    ?.kind,
            )
            repository.error = null
            vm.load()
            advanceUntilIdle()
            assertNull(vm.state.value.loadError)
            assertEquals(2L, vm.state.value.draft.id)
        }

    @Test
    fun `save error is shown and link failure turns the retry into an edit`() =
        runTest(mainDispatcher.testDispatcher) {
            val vm = viewModel()
            vm.edit(DraftChange.Title("x"))
            vm.edit(DraftChange.Monitors(setOf(1)))
            repository.error = KumaException(KumaError(KumaError.Kind.SERVER_REJECTED, "Invalid start date"))
            vm.save()
            advanceUntilIdle()
            assertEquals(
                "Invalid start date",
                vm.state.value.saveError
                    ?.message,
            )
            assertFalse(vm.state.value.done)
            vm.dismissSaveError()

            repository.error = null
            repository.linkError = KumaError(KumaError.Kind.SERVER_REJECTED, "Permission denied.")
            vm.save()
            advanceUntilIdle()
            assertTrue(vm.state.value.linkFailed)
            assertFalse(vm.state.value.done)
            val newId = vm.state.value.draft.id
            assertEquals(1_000L, newId)

            repository.linkError = null
            vm.save()
            advanceUntilIdle()
            assertEquals(newId, repository.savedDrafts.last().id)
            assertTrue(vm.state.value.done)
        }
}
