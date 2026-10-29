package net.kirkanos.uptimekuma.feature.settings

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import net.kirkanos.uptimekuma.core.model.AlertRecord
import net.kirkanos.uptimekuma.core.model.MonitorStatus
import net.kirkanos.uptimekuma.core.testing.FakeAlertHistoryRepository
import net.kirkanos.uptimekuma.core.testing.MainDispatcherExtension
import net.kirkanos.uptimekuma.feature.settings.history.AlertHistoryUiState
import net.kirkanos.uptimekuma.feature.settings.history.AlertHistoryViewModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@OptIn(ExperimentalCoroutinesApi::class)
class AlertHistoryViewModelTest {
    @JvmField
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension(UnconfinedTestDispatcher())

    private val older = AlertRecord(1_000, 1, "Web", MonitorStatus.DOWN, "timeout")
    private val newer = AlertRecord(2_000, 1, "Web", MonitorStatus.UP, "200 - OK")
    private val repository = FakeAlertHistoryRepository(listOf(older, newer))

    @Test
    fun `shows alerts newest first and clears them`() =
        runTest {
            val vm = AlertHistoryViewModel(repository)
            vm.state.test {
                assertEquals(AlertHistoryUiState.Loaded(listOf(newer, older)), awaitItem())
                vm.clear()
                assertEquals(AlertHistoryUiState.Loaded(emptyList()), awaitItem())
            }
            assertEquals(1, repository.clearCount)
        }

    @Test
    fun `new alerts appear`() =
        runTest {
            repository.alerts.value = emptyList()
            val vm = AlertHistoryViewModel(repository)
            vm.state.test {
                assertEquals(AlertHistoryUiState.Loaded(emptyList()), awaitItem())
                repository.alerts.value = listOf(newer)
                assertEquals(AlertHistoryUiState.Loaded(listOf(newer)), awaitItem())
            }
        }
}
