package net.kirkanos.uptimekuma.feature.settings.server

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import net.kirkanos.uptimekuma.core.model.KumaError
import net.kirkanos.uptimekuma.core.model.ServerSettings
import net.kirkanos.uptimekuma.core.model.StatusPageConfig
import net.kirkanos.uptimekuma.core.testing.FakeServerSettingsRepository
import net.kirkanos.uptimekuma.core.testing.FakeSessionRepository
import net.kirkanos.uptimekuma.core.testing.FakeStatusPageRepository
import net.kirkanos.uptimekuma.core.testing.MainDispatcherExtension
import net.kirkanos.uptimekuma.core.testing.SampleData
import net.kirkanos.uptimekuma.feature.settings.server.common.AdminDone
import net.kirkanos.uptimekuma.feature.settings.server.common.AdminMessage
import net.kirkanos.uptimekuma.feature.settings.server.settings.ChromeTest
import net.kirkanos.uptimekuma.feature.settings.server.settings.EntryPageOption
import net.kirkanos.uptimekuma.feature.settings.server.settings.ExpiryDays
import net.kirkanos.uptimekuma.feature.settings.server.settings.ServerSettingsViewModel
import net.kirkanos.uptimekuma.feature.settings.server.settings.SettingsProblem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@OptIn(ExperimentalCoroutinesApi::class)
class ServerSettingsViewModelTest {
    @JvmField
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension()

    private val repository = FakeServerSettingsRepository(ServerSettings(serverTimezone = "Europe/Berlin"))
    private val statusPages =
        FakeStatusPageRepository(
            listOf(StatusPageConfig(slug = "zeta", title = "Zeta"), StatusPageConfig(slug = "dev", title = "alpha")),
        )
    private val session = FakeSessionRepository(SampleData.serverConfig)

    private fun TestScope.viewModel(): ServerSettingsViewModel {
        val vm = ServerSettingsViewModel(repository, statusPages, session)
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()
        return vm
    }

    @Test
    fun `loads settings, details, status pages and the app's server URL`() =
        runTest(mainDispatcher.testDispatcher) {
            val state = viewModel().state.value
            assertFalse(state.loading)
            assertEquals("Europe/Berlin", state.draft?.serverTimezone)
            assertFalse(state.dirty)
            assertFalse(state.canSave)
            assertEquals(listOf(EntryPageOption("dev", "alpha"), EntryPageOption("zeta", "Zeta")), state.statusPages)
            assertEquals(SampleData.serverConfig.baseUrl, state.appServerUrl)
            assertEquals("sqlite", state.details?.dbType)
        }

    @Test
    fun `edits, saves the whole object and reports success`() =
        runTest(mainDispatcher.testDispatcher) {
            val vm = viewModel()
            vm.update { it.copy(searchEngineIndex = true, entryPage = "statusPage-dev") }
            advanceUntilIdle()
            assertTrue(vm.state.value.canSave)
            vm.save()
            advanceUntilIdle()
            val state = vm.state.value
            assertEquals(listOf("load", "save"), repository.calls)
            assertTrue(repository.settings.searchEngineIndex)
            assertEquals("statusPage-dev", repository.settings.entryPage)
            assertFalse(state.dirty)
            assertEquals(AdminMessage.Done(AdminDone.SAVED), state.message)
            vm.consumeMessage()
            advanceUntilIdle()
            assertNull(vm.state.value.message)
        }

    @Test
    fun `reset discards local edits`() =
        runTest(mainDispatcher.testDispatcher) {
            val vm = viewModel()
            vm.update { it.copy(trustProxy = true) }
            advanceUntilIdle()
            vm.reset()
            advanceUntilIdle()
            assertFalse(vm.state.value.dirty)
        }

    @Test
    fun `invalid primary URL and negative retention block saving`() =
        runTest(mainDispatcher.testDispatcher) {
            val vm = viewModel()
            vm.update { it.copy(primaryBaseUrl = "status.example.org") }
            advanceUntilIdle()
            assertEquals(setOf(SettingsProblem.PRIMARY_URL), vm.state.value.problems)
            assertFalse(vm.state.value.canSave)
            vm.save()
            advanceUntilIdle()
            assertEquals(listOf("load"), repository.calls)
            vm.update { it.copy(primaryBaseUrl = "https://status.example.org/kuma", keepDataPeriodDays = -1) }
            advanceUntilIdle()
            assertEquals(setOf(SettingsProblem.KEEP_DAYS), vm.state.value.problems)
            vm.update { it.copy(keepDataPeriodDays = 0) }
            advanceUntilIdle()
            assertTrue(vm.state.value.canSave)
        }

    @Test
    fun `save failure keeps the draft and reports the error`() =
        runTest(mainDispatcher.testDispatcher) {
            val vm = viewModel()
            vm.update { it.copy(nscd = false) }
            advanceUntilIdle()
            repository.failWith = KumaError(KumaError.Kind.SERVER_REJECTED, "boom")
            vm.save()
            advanceUntilIdle()
            val state = vm.state.value
            assertTrue(state.dirty)
            assertFalse(state.saving)
            assertEquals(AdminMessage.Failed(KumaError(KumaError.Kind.SERVER_REJECTED, "boom")), state.message)
        }

    @Test
    fun `load failure shows an error and retries`() =
        runTest(mainDispatcher.testDispatcher) {
            repository.failWith = KumaError(KumaError.Kind.NETWORK)
            val vm = viewModel()
            assertEquals(
                KumaError.Kind.NETWORK,
                vm.state.value.loadError
                    ?.kind,
            )
            assertFalse(vm.state.value.loading)
            repository.failWith = null
            vm.load()
            advanceUntilIdle()
            assertNull(vm.state.value.loadError)
            assertEquals(
                "Europe/Berlin",
                vm.state.value.saved
                    ?.serverTimezone,
            )
        }

    @Test
    fun `toggles on the About screen are saved right away`() =
        runTest(mainDispatcher.testDispatcher) {
            val vm = viewModel()
            vm.updateAndSave { it.copy(checkUpdate = false) }
            advanceUntilIdle()
            assertFalse(repository.settings.checkUpdate)
            assertFalse(vm.state.value.dirty)
        }

    @Test
    fun `tests the chrome executable`() =
        runTest(mainDispatcher.testDispatcher) {
            val vm = viewModel()
            vm.update { it.copy(chromeExecutable = "/usr/bin/chromium") }
            advanceUntilIdle()
            vm.testChrome()
            advanceUntilIdle()
            advanceUntilIdle()
            assertEquals(ChromeTest.Found("120.0"), vm.state.value.chromeTest)
            assertEquals("testChrome:/usr/bin/chromium", repository.calls.last())
            repository.failWith = KumaError(KumaError.Kind.SERVER_REJECTED, "not found")
            vm.testChrome()
            advanceUntilIdle()
            assertTrue(vm.state.value.chromeTest is ChromeTest.Failed)
        }

    @Test
    fun `expiry days are added sorted and unique within limits`() {
        assertEquals(listOf(1, 7, 14), ExpiryDays.add(listOf(7, 14), 1))
        assertEquals(listOf(7, 14), ExpiryDays.add(listOf(7, 14), 7))
        assertNull(ExpiryDays.add(listOf(7), 0))
        assertNull(ExpiryDays.add(listOf(7), ExpiryDays.MAX + 1))
        assertNull(ExpiryDays.add(listOf(7), null))
    }
}
