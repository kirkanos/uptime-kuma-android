package net.kirkanos.uptimekuma.push.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import net.kirkanos.uptimekuma.core.model.KumaError
import net.kirkanos.uptimekuma.core.model.KumaException
import net.kirkanos.uptimekuma.core.model.PushRegistration
import net.kirkanos.uptimekuma.core.testing.MainDispatcherExtension
import net.kirkanos.uptimekuma.push.data.FakeDistributorGateway
import net.kirkanos.uptimekuma.push.data.FakeDistributorGateway.Companion.NTFY
import net.kirkanos.uptimekuma.push.data.FakeDistributorGateway.Companion.OTHER
import net.kirkanos.uptimekuma.push.data.FakeFirebaseSettingsStore
import net.kirkanos.uptimekuma.push.data.FakeKumaNotificationRepository
import net.kirkanos.uptimekuma.push.data.FakeRegistrationStore
import net.kirkanos.uptimekuma.push.data.FakeScheduler
import net.kirkanos.uptimekuma.push.data.FakeTokenStore
import net.kirkanos.uptimekuma.push.data.PushRepositoryImpl
import net.kirkanos.uptimekuma.push.data.PushServiceEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.Optional

@OptIn(ExperimentalCoroutinesApi::class)
class PushSetupViewModelTest {
    @JvmField
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension(UnconfinedTestDispatcher())

    private val kuma = FakeKumaNotificationRepository()
    private val gateway = FakeDistributorGateway()
    private val store = FakeRegistrationStore()
    private val tokens = FakeTokenStore()

    private fun viewModel(): PushSetupViewModel {
        val dispatcher = mainDispatcher.testDispatcher
        val relayTokens = FakeTokenStore()
        val firebaseSettings = FakeFirebaseSettingsStore()
        val repository =
            PushRepositoryImpl(
                kuma,
                gateway,
                store,
                tokens,
                FakeScheduler(),
                { "Pixel" },
                dispatcher,
                Optional.empty(),
                firebaseSettings,
                relayTokens,
            )
        gateway.onRegister = { repository.onServiceEvent(PushServiceEvent.NewEndpoint(ENDPOINT)) }
        return PushSetupViewModel(repository, gateway, tokens, relayTokens, firebaseSettings, dispatcher)
    }

    @Test
    fun `no distributor shows the install step`() =
        runTest {
            gateway.installed = emptyList()
            val vm = viewModel()
            assertEquals(PushSetupPhase.InstallDistributor, vm.state.value.phase)

            gateway.installed = listOf(NTFY)
            vm.refresh()
            assertEquals(PushSetupPhase.Form, vm.state.value.phase)
            assertEquals(NTFY, vm.state.value.selectedDistributor)
        }

    @Test
    fun `several distributors without saved choice need a pick`() =
        runTest {
            gateway.installed = listOf(NTFY, OTHER)
            val vm = viewModel()
            assertEquals(2, vm.state.value.distributors.size)
            assertNull(vm.state.value.selectedDistributor)
            vm.onTokenChange("tk")
            assertFalse(vm.state.value.canSubmit)
            vm.onDistributorSelected(OTHER)
            assertTrue(vm.state.value.canSubmit)
        }

    @Test
    fun `successful setup ends active and test can be sent`() =
        runTest {
            val vm = viewModel()
            assertTrue(vm.state.value.applyToAll)
            vm.onTokenChange("tk_1")
            vm.setUp()
            assertEquals(PushSetupPhase.Active, vm.state.value.phase)
            assertEquals(100L, store.value.value?.kumaNotificationId)

            vm.sendTest()
            assertEquals(TestState.Sent, vm.state.value.testState)
            assertEquals(1, kuma.tested.size)
        }

    @Test
    fun `setup error is shown and the form stays`() =
        runTest {
            kuma.failure = KumaException(KumaError(KumaError.Kind.UNAUTHORIZED))
            val vm = viewModel()
            vm.onTokenChange("tk_1")
            vm.setUp()
            assertEquals(PushSetupPhase.Form, vm.state.value.phase)
            assertEquals(PushUiError.NotLoggedIn, vm.state.value.error)
        }

    @Test
    fun `missing token is reported without calling the repository`() =
        runTest {
            val vm = viewModel()
            vm.setUp()
            assertEquals(PushUiError.MissingToken, vm.state.value.error)
            assertEquals(0, gateway.registerCount)
        }

    @Test
    fun `already active setup opens on the active step with the stored token`() =
        runTest {
            store.value.value = PushRegistration(ENDPOINT, 5L, NTFY)
            tokens.token = "tk_saved"
            val vm = viewModel()
            assertEquals(PushSetupPhase.Active, vm.state.value.phase)
            assertEquals("tk_saved", vm.state.value.token)
        }

    @Test
    fun `error codes map back to localized errors`() {
        assertEquals(PushUiError.EndpointTimeout, PushUiError.fromCode("ENDPOINT_TIMEOUT"))
        assertEquals(PushUiError.Network, PushUiError.fromCode("KUMA_TIMEOUT"))
        assertEquals(PushUiError.Unknown(null), PushUiError.fromCode("whatever"))
    }

    private companion object {
        const val ENDPOINT = "https://ntfy.example.com/upAbCdEfGhIjKl?up=1"
    }
}
