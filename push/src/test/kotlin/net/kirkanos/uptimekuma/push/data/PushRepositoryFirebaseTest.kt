package net.kirkanos.uptimekuma.push.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import net.kirkanos.uptimekuma.core.domain.PushSetupState
import net.kirkanos.uptimekuma.core.domain.PushTransportConfig
import net.kirkanos.uptimekuma.core.model.FirebaseConfig
import net.kirkanos.uptimekuma.core.model.KumaError
import net.kirkanos.uptimekuma.core.model.KumaException
import net.kirkanos.uptimekuma.core.model.PushRegistration
import net.kirkanos.uptimekuma.core.model.PushTransport
import net.kirkanos.uptimekuma.push.data.FakeDistributorGateway.Companion.NTFY
import net.kirkanos.uptimekuma.push.firebase.FirebaseInitResult
import net.kirkanos.uptimekuma.push.firebase.FirebaseSettings
import net.kirkanos.uptimekuma.push.payload.WebhookTemplate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Optional

@OptIn(ExperimentalCoroutinesApi::class)
class PushRepositoryFirebaseTest {
    private val kuma = FakeKumaNotificationRepository()
    private val gateway = FakeDistributorGateway()
    private val store = FakeRegistrationStore()
    private val ntfyTokens = FakeTokenStore()
    private val relayTokens = FakeTokenStore()
    private val settings = FakeFirebaseSettingsStore()
    private val scheduler = FakeScheduler()
    private val fcm = FakeFirebaseTransport(token = FCM_TOKEN)

    private fun TestScope.repository(firebase: FakeFirebaseTransport? = fcm): PushRepositoryImpl =
        PushRepositoryImpl(
            notifications = kuma,
            gateway = gateway,
            registrationStore = store,
            tokenStore = ntfyTokens,
            scheduler = scheduler,
            deviceNameProvider = { "Pixel 8" },
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            firebase = Optional.ofNullable(firebase),
            firebaseSettings = settings,
            relayTokenStore = relayTokens,
        )

    private fun firebaseConfig(
        relayUrl: String = "https://relay.example.com/",
        relayToken: String = " relay_secret ",
        firebase: FirebaseConfig = FIREBASE,
    ) = PushTransportConfig.Firebase(relayUrl, relayToken, firebase)

    @Test
    fun `firebase setUp initializes Firebase, creates the relay webhook and becomes active`() =
        runTest {
            val repo = repository()

            repo.setUp(firebaseConfig(), applyToAllMonitors = true)

            assertEquals(listOf(FIREBASE), fcm.initialized)
            assertEquals(1, fcm.fetchCount)
            assertEquals(true, fcm.autoInit)
            assertEquals("relay_secret", relayTokens.token)
            assertEquals(FirebaseSettings("https://relay.example.com", FIREBASE), settings.settings)
            val (notification, applyExisting) = kuma.saveCalls.single()
            assertTrue(applyExisting)
            assertTrue(notification.isDefault)
            assertEquals("Pixel 8 (Firebase)", notification.name)
            assertEquals(WEBHOOK_URL, notification.fields["webhookURL"])
            assertEquals(WebhookTemplate.BODY, notification.fields["webhookCustomBody"])
            assertEquals(
                "{\"Authorization\":\"Bearer relay_secret\",\"Content-Type\":\"application/json\"}",
                notification.fields["webhookAdditionalHeaders"],
            )
            assertEquals(PushRegistration(WEBHOOK_URL, 100L, null, PushTransport.FIREBASE), store.value.value)
            assertEquals(PushSetupState.Active, repo.observeState().first())
            assertEquals(PushTransport.FIREBASE, repo.observeTransport().first())
            assertEquals(0, gateway.registerCount)
        }

    @Test
    fun `firebase values are trimmed before use`() =
        runTest {
            val padded =
                FirebaseConfig(
                    " kuma-push-1a2b3 ",
                    FIREBASE.applicationId + " ",
                    " " + FIREBASE.apiKey,
                    FIREBASE.gcmSenderId,
                )
            repository().setUp(firebaseConfig(firebase = padded), applyToAllMonitors = false)
            assertEquals(listOf(FIREBASE), fcm.initialized)
            assertFalse(kuma.saveCalls.single().second)
        }

    @Test
    fun `invalid input is rejected before Firebase or Kuma are touched`() =
        runTest {
            val repo = repository()
            val badUrl =
                assertThrows<PushSetupException> { repo.setUp(firebaseConfig(relayUrl = "relay.example.com"), true) }
            assertEquals(PushSetupException.Reason.RELAY_URL_INVALID, badUrl.reason)
            val noToken = assertThrows<PushSetupException> { repo.setUp(firebaseConfig(relayToken = " "), true) }
            assertEquals(PushSetupException.Reason.MISSING_RELAY_TOKEN, noToken.reason)
            val badKey =
                assertThrows<PushSetupException> {
                    repo.setUp(
                        firebaseConfig(firebase = FIREBASE.copy(apiKey = "nope")),
                        true,
                    )
                }
            assertEquals(PushSetupException.Reason.FIREBASE_CONFIG_INVALID, badKey.reason)
            assertEquals("API_KEY", badKey.message)
            assertEquals(PushSetupState.Error("FIREBASE_CONFIG_INVALID"), repo.observeState().first())
            assertTrue(fcm.initialized.isEmpty())
            assertTrue(kuma.saveCalls.isEmpty())
            assertNull(settings.settings)
        }

    @Test
    fun `foss build without Firebase transport refuses the Firebase setup`() =
        runTest {
            val repo = repository(firebase = null)
            assertEquals(setOf(PushTransport.NTFY), repo.availableTransports())
            val e = assertThrows<PushSetupException> { repo.setUp(firebaseConfig(), true) }
            assertEquals(PushSetupException.Reason.FIREBASE_UNAVAILABLE, e.reason)
        }

    @Test
    fun `full build offers both transports and never reports NoDistributor`() =
        runTest {
            gateway.installed = emptyList()
            val repo = repository()
            assertEquals(setOf(PushTransport.NTFY, PushTransport.FIREBASE), repo.availableTransports())
            assertEquals(PushSetupState.NotConfigured, repo.observeState().first())
        }

    @Test
    fun `other project values in this process require a restart but are saved`() =
        runTest {
            fcm.initResult = FirebaseInitResult.RESTART_REQUIRED
            val repo = repository()
            val e = assertThrows<PushSetupException> { repo.setUp(firebaseConfig(), true) }
            assertEquals(PushSetupException.Reason.FIREBASE_RESTART_REQUIRED, e.reason)
            assertEquals(FIREBASE, settings.settings?.firebase)
            assertEquals(0, fcm.fetchCount)
            assertTrue(kuma.saveCalls.isEmpty())
        }

    @Test
    fun `token failures surface with their reason`() =
        runTest {
            fcm.tokenFailure = PushSetupException(PushSetupException.Reason.PLAY_SERVICES_MISSING)
            val repo = repository()
            val e = assertThrows<PushSetupException> { repo.setUp(firebaseConfig(), true) }
            assertEquals(PushSetupException.Reason.PLAY_SERVICES_MISSING, e.reason)
            assertEquals(PushSetupState.Error("PLAY_SERVICES_MISSING"), repo.observeState().first())
            assertNull(store.value.value)
        }

    @Test
    fun `setting up firebase again updates the same Kuma notification`() =
        runTest {
            val repo = repository()
            repo.setUp(firebaseConfig(), true)
            fcm.token = "fcm-token-2"
            repo.setUp(firebaseConfig(), false)
            assertEquals(listOf(null, 100L), kuma.saveCalls.map { it.first.id })
            assertEquals("https://relay.example.com/v1/fcm/fcm-token-2", store.value.value?.endpoint)
            assertEquals(0, fcm.deleteCount)
        }

    @Test
    fun `switching from ntfy to firebase tears ntfy down after the new setup worked`() =
        runTest {
            val repo = repository()
            gateway.onRegister = { repo.onServiceEvent(PushServiceEvent.NewEndpoint(NTFY_ENDPOINT)) }
            repo.setUp(ntfy("tk"), true)
            assertEquals(100L, store.value.value?.kumaNotificationId)

            repo.setUp(firebaseConfig(), true)

            assertEquals(listOf(null, null), kuma.saveCalls.map { it.first.id })
            assertEquals(listOf(100L), kuma.deleted)
            assertEquals(1, gateway.unregisterCount)
            assertNull(ntfyTokens.token)
            assertEquals(PushRegistration(WEBHOOK_URL, 101L, null, PushTransport.FIREBASE), store.value.value)
            assertEquals(
                listOf(101L),
                kuma.stored.value.keys
                    .toList(),
            )
        }

    @Test
    fun `a failed firebase setup keeps the working ntfy setup`() =
        runTest {
            val repo = repository()
            gateway.onRegister = { repo.onServiceEvent(PushServiceEvent.NewEndpoint(NTFY_ENDPOINT)) }
            repo.setUp(ntfy("tk"), true)
            fcm.tokenFailure = PushSetupException(PushSetupException.Reason.FCM_UNAVAILABLE)

            assertThrows<PushSetupException> { repo.setUp(firebaseConfig(), true) }

            assertEquals(PushRegistration(NTFY_ENDPOINT, 100L, NTFY), store.value.value)
            assertTrue(kuma.deleted.isEmpty())
            assertEquals(0, gateway.unregisterCount)
            assertEquals("tk", ntfyTokens.token)
        }

    @Test
    fun `switching from firebase to ntfy deletes the FCM token and the relay webhook`() =
        runTest {
            val repo = repository()
            repo.setUp(firebaseConfig(), true)
            gateway.onRegister = { repo.onServiceEvent(PushServiceEvent.NewEndpoint(NTFY_ENDPOINT)) }

            repo.setUp(ntfy("tk"), false)

            assertEquals(listOf(100L), kuma.deleted)
            assertEquals(1, fcm.deleteCount)
            assertEquals(false, fcm.autoInit)
            assertNull(relayTokens.token)
            assertEquals(PushRegistration(NTFY_ENDPOINT, 101L, NTFY), store.value.value)
            assertEquals(
                "Pixel 8 (UnifiedPush)",
                kuma.stored.value
                    .getValue(101L)
                    .name,
            )
            // Firebase values stay for the next time (prefill); only the secret is gone.
            assertEquals(FIREBASE, settings.settings?.firebase)
        }

    @Test
    fun `tearDown of firebase deletes the Kuma notification and the FCM token`() =
        runTest {
            val repo = repository()
            repo.setUp(firebaseConfig(), true)

            repo.tearDown()

            assertEquals(listOf(100L), kuma.deleted)
            assertEquals(1, fcm.deleteCount)
            assertEquals(0, gateway.unregisterCount)
            assertNull(relayTokens.token)
            assertNull(store.value.value)
            assertNull(repo.observeTransport().first())
        }

    @Test
    fun `sendTest uses the relay webhook and token`() =
        runTest {
            val repo = repository()
            repo.setUp(firebaseConfig(), true)
            repo.sendTest()
            val tested = kuma.tested.single()
            assertEquals("Pixel 8 (Firebase)", tested.name)
            assertEquals(WEBHOOK_URL, tested.fields["webhookURL"])
            assertTrue((tested.fields["webhookAdditionalHeaders"] as String).contains("Bearer relay_secret"))
        }

    @Test
    fun `a new FCM token updates the webhook URL in Kuma via the worker`() =
        runTest {
            val repo = repository()
            repo.setUp(firebaseConfig(), true)

            repo.handleNewFcmToken(FCM_TOKEN)
            assertEquals(0, scheduler.endpointUpdates)

            repo.handleNewFcmToken("fcm-token-rotated")
            assertEquals(1, scheduler.endpointUpdates)
            assertEquals("https://relay.example.com/v1/fcm/fcm-token-rotated", store.value.value?.endpoint)

            repo.updateKumaEndpoint()
            val (notification, applyExisting) = kuma.saveCalls.last()
            assertEquals(100L, notification.id)
            assertEquals("Pixel 8 (Firebase)", notification.name)
            assertEquals("https://relay.example.com/v1/fcm/fcm-token-rotated", notification.fields["webhookURL"])
            assertFalse(applyExisting)
        }

    @Test
    fun `a new FCM token is ignored while ntfy is active or nothing is set up`() =
        runTest {
            settings.settings = FirebaseSettings("https://relay.example.com", FIREBASE)
            val repo = repository()
            repo.handleNewFcmToken("t1")
            assertNull(store.value.value)

            store.value.value = PushRegistration(NTFY_ENDPOINT, 7L, NTFY)
            repo.handleNewFcmToken("t2")
            assertEquals(PushRegistration(NTFY_ENDPOINT, 7L, NTFY), store.value.value)
            assertEquals(0, scheduler.endpointUpdates)
        }

    @Test
    fun `UnifiedPush leftovers do not disturb an active firebase setup`() =
        runTest {
            val repo = repository()
            repo.setUp(firebaseConfig(), true)

            repo.handleNewEndpoint(NTFY_ENDPOINT)
            repo.handleUnregistered()
            repo.reRegisterIfConfigured()

            assertEquals(PushRegistration(WEBHOOK_URL, 100L, null, PushTransport.FIREBASE), store.value.value)
            assertTrue(kuma.deleted.isEmpty())
            assertEquals(0, gateway.registerCount)
            assertEquals(0, scheduler.endpointUpdates)
        }

    @Test
    fun `switching still succeeds when the old Kuma notification cannot be deleted`() =
        runTest {
            store.value.value = PushRegistration(NTFY_ENDPOINT, 55L, NTFY) // unknown to Kuma -> delete fails
            val repo = repository()
            repo.setUp(firebaseConfig(), true)
            assertEquals(PushTransport.FIREBASE, store.value.value?.transport)
            assertEquals(1, gateway.unregisterCount)
        }

    @Test
    fun `Kuma errors during firebase setup are reported like for ntfy`() =
        runTest {
            kuma.failure = KumaException(KumaError(KumaError.Kind.NETWORK))
            val repo = repository()
            assertThrows<KumaException> { repo.setUp(firebaseConfig(), true) }
            assertEquals(PushSetupState.Error("KUMA_NETWORK"), repo.observeState().first())
        }

    private companion object {
        const val FCM_TOKEN = "eXaMpLe:APA91bF_token-1"
        const val WEBHOOK_URL = "https://relay.example.com/v1/fcm/$FCM_TOKEN"
        const val NTFY_ENDPOINT = "https://ntfy.example.com/upAbCdEfGhIjKl?up=1"
        val FIREBASE =
            FirebaseConfig(
                projectId = "kuma-push-1a2b3",
                applicationId = "1:123456789012:android:0123456789abcdef",
                apiKey = "AIzaSyA1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6Q",
                gcmSenderId = "123456789012",
            )
    }
}
