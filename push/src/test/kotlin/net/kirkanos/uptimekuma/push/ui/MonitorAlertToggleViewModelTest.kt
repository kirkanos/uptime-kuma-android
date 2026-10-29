package net.kirkanos.uptimekuma.push.ui

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import net.kirkanos.uptimekuma.core.model.MonitorMute
import net.kirkanos.uptimekuma.core.model.MuteDuration
import net.kirkanos.uptimekuma.core.testing.FakeAlertPreferencesRepository
import net.kirkanos.uptimekuma.core.testing.MainDispatcherExtension
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@OptIn(ExperimentalCoroutinesApi::class)
class MonitorAlertToggleViewModelTest {
    @JvmField
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension(UnconfinedTestDispatcher())

    private val repository = FakeAlertPreferencesRepository()
    private val now = 1_761_700_000_000L

    @Test
    fun `mute durations and unmute`() =
        runTest {
            val vm = MonitorAlertToggleViewModel(monitorId = 3, repository = repository)
            vm.mute.test {
                assertNull(awaitItem())

                vm.mute(MuteDuration.ONE_HOUR, nowMillis = now)
                assertEquals(MonitorMute(3, now + 3_600_000), awaitItem())

                vm.mute(MuteDuration.EIGHT_HOURS, nowMillis = now)
                assertEquals(MonitorMute(3, now + 8 * 3_600_000), awaitItem())

                vm.mute(MuteDuration.ONE_DAY, nowMillis = now)
                assertEquals(MonitorMute(3, now + 24 * 3_600_000), awaitItem())

                vm.mute(MuteDuration.UNTIL_UNMUTED, nowMillis = now)
                assertEquals(MonitorMute(3, null), awaitItem())

                vm.unmute()
                assertNull(awaitItem())
            }
        }

    @Test
    fun `mutes of other monitors are ignored`() =
        runTest {
            repository.mute(4, null)
            val vm = MonitorAlertToggleViewModel(monitorId = 3, repository = repository)
            vm.mute.test {
                assertNull(awaitItem())
                expectNoEvents()
            }
        }
}
