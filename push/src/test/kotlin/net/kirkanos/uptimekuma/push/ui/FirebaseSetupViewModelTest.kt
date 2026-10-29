package net.kirkanos.uptimekuma.push.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import net.kirkanos.uptimekuma.core.model.FirebaseConfig
import net.kirkanos.uptimekuma.core.model.FirebaseConfigField
import net.kirkanos.uptimekuma.core.model.PushRegistration
import net.kirkanos.uptimekuma.core.model.PushTransport
import net.kirkanos.uptimekuma.core.testing.MainDispatcherExtension
import net.kirkanos.uptimekuma.push.data.FakeDistributorGateway
import net.kirkanos.uptimekuma.push.data.FakeFirebaseSettingsStore
import net.kirkanos.uptimekuma.push.data.FakeFirebaseTransport
import net.kirkanos.uptimekuma.push.data.FakeKumaNotificationRepository
import net.kirkanos.uptimekuma.push.data.FakeRegistrationStore
import net.kirkanos.uptimekuma.push.data.FakeScheduler
import net.kirkanos.uptimekuma.push.data.FakeTokenStore
import net.kirkanos.uptimekuma.push.data.PushRepositoryImpl
import net.kirkanos.uptimekuma.push.data.PushServiceEvent
import net.kirkanos.uptimekuma.push.data.PushSetupException
import net.kirkanos.uptimekuma.push.firebase.FirebaseInitResult
import net.kirkanos.uptimekuma.push.firebase.FirebaseSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.io.IOException
import java.util.Optional

@OptIn(ExperimentalCoroutinesApi::class)
class FirebaseSetupViewModelTest {
    @JvmField
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension(UnconfinedTestDispatcher())

    private val kuma = FakeKumaNotificationRepository()
    private val gateway = FakeDistributorGateway()
    private val store = FakeRegistrationStore()
    private val tokens = FakeTokenStore()
    private val relayTokens = FakeTokenStore()
    private val settings = FakeFirebaseSettingsStore()
    private val fcm = FakeFirebaseTransport(token = "fcm1")

    private fun viewModel(firebase: FakeFirebaseTransport? = fcm): PushSetupViewModel {
        val dispatcher = mainDispatcher.testDispatcher
        val repository =
            PushRepositoryImpl(
                kuma,
                gateway,
                store,
                tokens,
                FakeScheduler(),
                { "Pixel" },
                dispatcher,
                Optional.ofNullable(firebase),
                settings,
                relayTokens,
            )
        gateway.onRegister = { repository.onServiceEvent(PushServiceEvent.NewEndpoint(NTFY_ENDPOINT)) }
        return PushSetupViewModel(repository, gateway, tokens, relayTokens, settings, dispatcher)
    }

    private fun PushSetupViewModel.fillFirebaseForm() {
        onRelayUrlChange("https://relay.example.com")
        onRelayTokenChange("relay_secret")
        FirebaseConfigField.entries.forEach { onFirebaseFieldChange(it, VALID_FORM.value(it)) }
    }

    @Test
    fun `full build starts with the transport picker`() =
        runTest {
            val vm = viewModel()
            assertEquals(PushSetupPhase.ChooseTransport, vm.state.value.phase)
            assertTrue(vm.state.value.canChooseTransport)

            vm.onTransportSelected(PushTransport.FIREBASE)
            assertEquals(PushSetupPhase.FirebaseForm, vm.state.value.phase)
            vm.chooseTransport()
            assertEquals(PushSetupPhase.ChooseTransport, vm.state.value.phase)
            vm.onTransportSelected(PushTransport.NTFY)
            assertEquals(PushSetupPhase.Form, vm.state.value.phase)
        }

    @Test
    fun `foss build has no picker and ignores Firebase`() =
        runTest {
            val vm = viewModel(firebase = null)
            assertEquals(PushSetupPhase.Form, vm.state.value.phase)
            assertFalse(vm.state.value.canChooseTransport)
            vm.onTransportSelected(PushTransport.FIREBASE)
            assertEquals(PushSetupPhase.Form, vm.state.value.phase)
            vm.chooseTransport()
            assertEquals(PushSetupPhase.Form, vm.state.value.phase)
        }

    @Test
    fun `resume refresh keeps the picker and the firebase form`() =
        runTest {
            val vm = viewModel()
            vm.refresh()
            assertEquals(PushSetupPhase.ChooseTransport, vm.state.value.phase)
            vm.onTransportSelected(PushTransport.FIREBASE)
            vm.refresh()
            assertEquals(PushSetupPhase.FirebaseForm, vm.state.value.phase)
        }

    @Test
    fun `firebase setup ends active and test can be sent`() =
        runTest {
            val vm = viewModel()
            vm.onTransportSelected(PushTransport.FIREBASE)
            assertFalse(vm.state.value.canSubmit)
            vm.fillFirebaseForm()
            assertTrue(vm.state.value.canSubmit)

            vm.setUp()

            assertEquals(PushSetupPhase.Active, vm.state.value.phase)
            assertEquals(PushTransport.FIREBASE, vm.state.value.activeTransport)
            assertEquals("https://relay.example.com/v1/fcm/fcm1", store.value.value?.endpoint)
            vm.sendTest()
            assertEquals(TestState.Sent, vm.state.value.testState)
        }

    @Test
    fun `live validation marks filled invalid fields and http`() =
        runTest {
            val vm = viewModel()
            vm.onTransportSelected(PushTransport.FIREBASE)
            vm.onFirebaseFieldChange(FirebaseConfigField.API_KEY, "AIza123")
            assertEquals(setOf(FirebaseConfigField.API_KEY), vm.state.value.firebase.invalidFields)
            vm.onRelayUrlChange("http://10.0.0.2:8080")
            assertEquals(
                net.kirkanos.uptimekuma.push.firebase.RelayUrl.Check.INSECURE,
                vm.state.value.firebase.relayCheck,
            )
        }

    @Test
    fun `obviously fake values give a localized error without touching Firebase`() =
        runTest {
            val vm = viewModel()
            vm.onTransportSelected(PushTransport.FIREBASE)
            vm.fillFirebaseForm()
            vm.onFirebaseFieldChange(FirebaseConfigField.API_KEY, "fake")
            vm.onFirebaseFieldChange(FirebaseConfigField.PROJECT_ID, "x")

            vm.setUp()

            assertEquals(PushSetupPhase.FirebaseForm, vm.state.value.phase)
            assertEquals(
                PushUiError.FirebaseConfigInvalid(setOf(FirebaseConfigField.API_KEY, FirebaseConfigField.PROJECT_ID)),
                vm.state.value.error,
            )
            assertTrue(fcm.initialized.isEmpty())
        }

    @Test
    fun `invalid relay URL is reported first`() =
        runTest {
            val vm = viewModel()
            vm.onTransportSelected(PushTransport.FIREBASE)
            vm.fillFirebaseForm()
            vm.onRelayUrlChange("relay.example.com")
            vm.setUp()
            assertEquals(PushUiError.RelayUrlInvalid, vm.state.value.error)
        }

    @Test
    fun `restart required is shown and the form stays`() =
        runTest {
            fcm.initResult = FirebaseInitResult.RESTART_REQUIRED
            val vm = viewModel()
            vm.onTransportSelected(PushTransport.FIREBASE)
            vm.fillFirebaseForm()
            vm.setUp()
            assertEquals(PushSetupPhase.FirebaseForm, vm.state.value.phase)
            assertEquals(PushUiError.RestartRequired, vm.state.value.error)
        }

    @Test
    fun `FCM failures map to their UI errors`() =
        runTest {
            fcm.tokenFailure = PushSetupException(PushSetupException.Reason.FCM_FAILED, "PHONE_REGISTRATION_ERROR")
            val vm = viewModel()
            vm.onTransportSelected(PushTransport.FIREBASE)
            vm.fillFirebaseForm()
            vm.setUp()
            assertEquals(PushUiError.FcmFailed("PHONE_REGISTRATION_ERROR"), vm.state.value.error)
        }

    @Test
    fun `google-services json import fills the form for this package`() =
        runTest {
            val vm = viewModel()
            vm.onTransportSelected(PushTransport.FIREBASE)
            vm.importGoogleServices(read = { googleServices(DEBUG_PACKAGE) }, packageName = DEBUG_PACKAGE)
            assertNull(vm.state.value.error)
            assertTrue(vm.state.value.firebaseImported)
            assertEquals(VALID_FORM.config, vm.state.value.firebase.config)
        }

    @Test
    fun `google-services json without our package is a clear error`() =
        runTest {
            val vm = viewModel()
            vm.importGoogleServices(read = { googleServices("net.kirkanos.uptimekuma") }, packageName = DEBUG_PACKAGE)
            assertEquals(
                PushUiError.GoogleServicesNoMatch(DEBUG_PACKAGE, listOf("net.kirkanos.uptimekuma")),
                vm.state.value.error,
            )
            assertEquals("", vm.state.value.firebase.projectId)
        }

    @Test
    fun `unreadable or foreign files are errors`() =
        runTest {
            val vm = viewModel()
            vm.importGoogleServices(read = { throw IOException("gone") }, packageName = DEBUG_PACKAGE)
            assertEquals(PushUiError.GoogleServicesInvalid(null), vm.state.value.error)
            vm.importGoogleServices(read = { "{\"hello\": 1}" }, packageName = DEBUG_PACKAGE)
            assertEquals(PushUiError.GoogleServicesInvalid("project_info"), vm.state.value.error)
        }

    @Test
    fun `stored firebase setup opens active with the form prefilled`() =
        runTest {
            store.value.value = PushRegistration("https://relay.example.com/v1/fcm/x", 3L, null, PushTransport.FIREBASE)
            settings.settings = FirebaseSettings("https://relay.example.com", VALID_FORM.config)
            relayTokens.token = "relay_saved"
            val vm = viewModel()
            assertEquals(PushSetupPhase.Active, vm.state.value.phase)
            assertEquals(PushTransport.FIREBASE, vm.state.value.activeTransport)
            assertEquals("relay_saved", vm.state.value.firebase.relayToken)
            assertEquals(VALID_FORM.config, vm.state.value.firebase.config)

            vm.reconfigure()
            assertEquals(PushSetupPhase.FirebaseForm, vm.state.value.phase)
        }

    @Test
    fun `disable returns to the picker`() =
        runTest {
            val vm = viewModel()
            vm.onTransportSelected(PushTransport.FIREBASE)
            vm.fillFirebaseForm()
            vm.setUp()
            vm.disable()
            assertEquals(PushSetupPhase.ChooseTransport, vm.state.value.phase)
            assertNull(vm.state.value.activeTransport)
            assertEquals("", vm.state.value.firebase.relayToken)
            assertEquals(1, fcm.deleteCount)
        }

    @Test
    fun `switching from active ntfy to firebase via the picker`() =
        runTest {
            val vm = viewModel()
            vm.onTransportSelected(PushTransport.NTFY)
            vm.onTokenChange("tk")
            vm.setUp()
            assertEquals(PushTransport.NTFY, vm.state.value.activeTransport)

            vm.reconfigure()
            vm.chooseTransport()
            vm.onTransportSelected(PushTransport.FIREBASE)
            vm.fillFirebaseForm()
            vm.setUp()

            assertEquals(PushTransport.FIREBASE, vm.state.value.activeTransport)
            assertEquals(listOf(100L), kuma.deleted)
            assertEquals(1, gateway.unregisterCount)
        }

    private fun googleServices(packageName: String) =
        """
        {"project_info": {"project_number": "123456789012", "project_id": "kuma-push-1a2b3"},
         "client": [{"client_info": {"mobilesdk_app_id": "1:123456789012:android:0123456789abcdef",
                                     "android_client_info": {"package_name": "$packageName"}},
                     "api_key": [{"current_key": "AIzaSyA1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6Q"}]}]}
        """.trimIndent()

    private companion object {
        const val NTFY_ENDPOINT = "https://ntfy.example.com/upAbCdEfGhIjKl?up=1"
        const val DEBUG_PACKAGE = "net.kirkanos.uptimekuma.debug"
        val VALID_FORM =
            FirebaseFormState().with(
                FirebaseConfig(
                    projectId = "kuma-push-1a2b3",
                    applicationId = "1:123456789012:android:0123456789abcdef",
                    apiKey = "AIzaSyA1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6Q",
                    gcmSenderId = "123456789012",
                ),
            )
    }
}
