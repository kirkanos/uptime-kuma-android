package net.kirkanos.uptimekuma.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import net.kirkanos.uptimekuma.core.testing.FakeSessionRepository
import net.kirkanos.uptimekuma.core.testing.MainDispatcherExtension
import net.kirkanos.uptimekuma.core.testing.SampleData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelTest {
    @JvmField
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension()

    @Test
    fun `gate follows the server config`() =
        runTest(mainDispatcher.testDispatcher) {
            val session = FakeSessionRepository()
            val vm = AppViewModel(session)
            assertEquals(SessionGate.Loading, vm.gate.value)
            advanceUntilIdle()
            assertEquals(SessionGate.Ready(configured = false), vm.gate.value)

            session.serverConfig.value = SampleData.serverConfig
            advanceUntilIdle()
            assertEquals(SessionGate.Ready(configured = true), vm.gate.value)

            session.logout()
            advanceUntilIdle()
            assertEquals(SessionGate.Ready(configured = false), vm.gate.value)
        }

    @Test
    fun `deep link monitor id is held until consumed`() =
        runTest(mainDispatcher.testDispatcher) {
            val vm = AppViewModel(FakeSessionRepository())
            vm.openMonitor(42)
            assertEquals(42L, vm.pendingMonitorId.value)
            vm.consumePendingMonitor()
            assertNull(vm.pendingMonitorId.value)
        }
}
