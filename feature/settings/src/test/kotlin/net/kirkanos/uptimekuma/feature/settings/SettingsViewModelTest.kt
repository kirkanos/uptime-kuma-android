package net.kirkanos.uptimekuma.feature.settings

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import net.kirkanos.uptimekuma.core.model.QuietHours
import net.kirkanos.uptimekuma.core.testing.FakeAlertPreferencesRepository
import net.kirkanos.uptimekuma.core.testing.FakeServerInfoRepository
import net.kirkanos.uptimekuma.core.testing.FakeSessionRepository
import net.kirkanos.uptimekuma.core.testing.MainDispatcherExtension
import net.kirkanos.uptimekuma.core.testing.SampleData
import net.kirkanos.uptimekuma.feature.settings.main.SettingsViewModel
import net.kirkanos.uptimekuma.feature.settings.main.formatMinuteOfDay
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    @JvmField
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension()

    private val session = FakeSessionRepository(SampleData.serverConfig)
    private val serverInfo = FakeServerInfoRepository(SampleData.serverInfo)
    private val alerts = FakeAlertPreferencesRepository()

    private fun TestScope.viewModel(): SettingsViewModel {
        val vm = SettingsViewModel(session, serverInfo, alerts)
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()
        return vm
    }

    @Test
    fun `shows server, account, version and alert preferences`() =
        runTest(mainDispatcher.testDispatcher) {
            val state = viewModel().state.value
            assertFalse(state.loading)
            assertEquals(SampleData.serverConfig, state.serverConfig)
            assertEquals("2.5.8", state.serverInfo?.version)
            assertTrue(state.alertPreferences.notifyOnUp)
        }

    @Test
    fun `toggles recovery and certificate alerts`() =
        runTest(mainDispatcher.testDispatcher) {
            val vm = viewModel()
            vm.setNotifyOnUp(false)
            vm.setNotifyOnCertExpiry(false)
            advanceUntilIdle()
            assertFalse(vm.state.value.alertPreferences.notifyOnUp)
            assertFalse(vm.state.value.alertPreferences.notifyOnCertExpiry)
        }

    @Test
    fun `edits quiet hours step by step`() =
        runTest(mainDispatcher.testDispatcher) {
            val vm = viewModel()
            vm.setQuietHoursEnabled(true)
            advanceUntilIdle()
            vm.setQuietHoursStart(23 * 60)
            advanceUntilIdle()
            vm.setQuietHoursEnd(6 * 60)
            advanceUntilIdle()
            assertEquals(QuietHours(true, 23 * 60, 6 * 60), alerts.preferences.value.quietHours)
            assertEquals(alerts.preferences.value, vm.state.value.alertPreferences)
        }

    @Test
    fun `logout clears the session`() =
        runTest(mainDispatcher.testDispatcher) {
            val vm = viewModel()
            vm.logout()
            advanceUntilIdle()
            assertEquals(1, session.logoutCount)
            assertNull(vm.state.value.serverConfig)
        }

    @Test
    fun `minute of day is formatted per locale`() {
        assertEquals("22:30", formatMinuteOfDay(22 * 60 + 30, Locale.GERMANY))
        assertEquals("07:05", formatMinuteOfDay(7 * 60 + 5, Locale.GERMANY))
    }
}
