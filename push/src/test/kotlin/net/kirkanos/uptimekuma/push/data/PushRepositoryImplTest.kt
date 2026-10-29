package net.kirkanos.uptimekuma.push.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import net.kirkanos.uptimekuma.core.domain.PushSetupState
import net.kirkanos.uptimekuma.core.model.KumaError
import net.kirkanos.uptimekuma.core.model.KumaException
import net.kirkanos.uptimekuma.core.model.PushRegistration
import net.kirkanos.uptimekuma.push.data.FakeDistributorGateway.Companion.NTFY
import net.kirkanos.uptimekuma.push.data.FakeDistributorGateway.Companion.OTHER
import net.kirkanos.uptimekuma.push.payload.WebhookTemplate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Optional

@OptIn(ExperimentalCoroutinesApi::class)
class PushRepositoryImplTest {
    private val kuma = FakeKumaNotificationRepository()
    private val gateway = FakeDistributorGateway()
    private val store = FakeRegistrationStore()
    private val tokens = FakeTokenStore()
    private val scheduler = FakeScheduler()

    private fun TestScope.repository(): PushRepositoryImpl =
        PushRepositoryImpl(
            notifications = kuma,
            gateway = gateway,
            registrationStore = store,
            tokenStore = tokens,
            scheduler = scheduler,
            deviceNameProvider = { "Pixel 8" },
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            firebase = Optional.empty(),
            firebaseSettings = FakeFirebaseSettingsStore(),
            relayTokenStore = FakeTokenStore(),
        )

    private fun PushRepositoryImpl.answerRegisterWith(url: String) {
        gateway.onRegister = { onServiceEvent(PushServiceEvent.NewEndpoint(url)) }
    }

    @Test
    fun `setUp registers, creates the Kuma webhook and becomes active`() =
        runTest {
            val repo = repository()
            repo.answerRegisterWith(ENDPOINT)

            repo.setUp(ntfy(" tk_secret "), applyToAllMonitors = true)

            assertEquals(NTFY, gateway.saved)
            assertEquals(1, gateway.registerCount)
            assertEquals("tk_secret", tokens.token)
            val (notification, applyExisting) = kuma.saveCalls.single()
            assertTrue(applyExisting)
            assertNull(notification.id)
            assertEquals("Pixel 8 (UnifiedPush)", notification.name)
            assertEquals("webhook", notification.type)
            assertEquals(ENDPOINT, notification.fields["webhookURL"])
            assertEquals(WebhookTemplate.BODY, notification.fields["webhookCustomBody"])
            assertEquals(
                "{\"Authorization\":\"Bearer tk_secret\",\"Content-Type\":\"application/json\"}",
                notification.fields["webhookAdditionalHeaders"],
            )
            assertEquals(PushRegistration(ENDPOINT, 100L, NTFY), store.value.value)
            assertEquals(PushSetupState.Active, repo.observeState().first())
        }

    @Test
    fun `setUp passes applyToAllMonitors false through`() =
        runTest {
            val repo = repository()
            repo.answerRegisterWith(ENDPOINT)
            repo.setUp(ntfy("tk"), applyToAllMonitors = false)
            assertEquals(false, kuma.saveCalls.single().second)
        }

    @Test
    fun `setUp uses the requested distributor when several are installed`() =
        runTest {
            gateway.installed = listOf(NTFY, OTHER)
            val repo = repository()
            repo.answerRegisterWith(ENDPOINT)
            repo.setUp(ntfy("tk", OTHER), applyToAllMonitors = true)
            assertEquals(OTHER, gateway.saved)
            assertEquals(OTHER, store.value.value?.distributor)
        }

    @Test
    fun `setUp without distributor fails with NO_DISTRIBUTOR`() =
        runTest {
            gateway.installed = emptyList()
            val repo = repository()
            val e = assertThrows<PushSetupException> { repo.setUp(ntfy("tk"), true) }
            assertEquals(PushSetupException.Reason.NO_DISTRIBUTOR, e.reason)
            assertEquals(PushSetupState.Error("NO_DISTRIBUTOR"), repo.observeState().first())
            assertTrue(kuma.saveCalls.isEmpty())
        }

    @Test
    fun `setUp with blank token fails before touching anything`() =
        runTest {
            val repo = repository()
            val e = assertThrows<PushSetupException> { repo.setUp(ntfy("  "), true) }
            assertEquals(PushSetupException.Reason.MISSING_TOKEN, e.reason)
            assertEquals(0, gateway.registerCount)
        }

    @Test
    fun `setUp times out after 20 seconds without endpoint`() =
        runTest {
            val repo = repository()
            var failure: Throwable? = null
            val job = launch { failure = runCatching { repo.setUp(ntfy("tk"), true) }.exceptionOrNull() }
            advanceTimeBy(PushRepositoryImpl.ENDPOINT_TIMEOUT_MILLIS - 1)
            assertNull(failure)
            assertEquals(PushSetupState.Registering, repo.observeState().first())
            advanceTimeBy(2)
            job.join()
            assertEquals(PushSetupException.Reason.ENDPOINT_TIMEOUT, (failure as PushSetupException).reason)
            assertEquals(PushSetupState.Error("ENDPOINT_TIMEOUT"), repo.observeState().first())
        }

    @Test
    fun `setUp ignores endpoints delivered before it started`() =
        runTest {
            val repo = repository()
            repo.onServiceEvent(PushServiceEvent.NewEndpoint("https://stale/upOLD?up=1"))
            repo.answerRegisterWith(ENDPOINT)
            repo.setUp(ntfy("tk"), true)
            assertEquals(ENDPOINT, store.value.value?.endpoint)
        }

    @Test
    fun `setUp ignores unregistration of the previous distributor while waiting`() =
        runTest {
            val repo = repository()
            gateway.onRegister = {
                repo.onServiceEvent(PushServiceEvent.Unregistered)
                repo.onServiceEvent(PushServiceEvent.NewEndpoint(ENDPOINT))
            }
            repo.setUp(ntfy("tk"), true)
            assertEquals(ENDPOINT, store.value.value?.endpoint)
        }

    @Test
    fun `setUp maps ACTION_REQUIRED registration failure`() =
        runTest {
            val repo = repository()
            gateway.onRegister = { repo.onServiceEvent(PushServiceEvent.RegistrationFailed("ACTION_REQUIRED")) }
            val e = assertThrows<PushSetupException> { repo.setUp(ntfy("tk"), true) }
            assertEquals(PushSetupException.Reason.ACTION_REQUIRED, e.reason)
        }

    @Test
    fun `setUp propagates Kuma errors and exposes them as error code`() =
        runTest {
            val repo = repository()
            repo.answerRegisterWith(ENDPOINT)
            kuma.failure = KumaException(KumaError(KumaError.Kind.NETWORK))
            assertThrows<KumaException> { repo.setUp(ntfy("tk"), true) }
            assertEquals(PushSetupState.Error("KUMA_NETWORK"), repo.observeState().first())
        }

    @Test
    fun `setting up again updates the existing Kuma notification`() =
        runTest {
            val repo = repository()
            repo.answerRegisterWith(ENDPOINT)
            repo.setUp(ntfy("tk"), true)
            repo.answerRegisterWith(ENDPOINT_2)
            repo.setUp(ntfy("tk2"), false)

            assertEquals(listOf(null, 100L), kuma.saveCalls.map { it.first.id })
            assertEquals(PushRegistration(ENDPOINT_2, 100L, NTFY), store.value.value)
        }

    @Test
    fun `setting up again recreates the notification if it was deleted in Kuma`() =
        runTest {
            store.value.value = PushRegistration(ENDPOINT, 55L, NTFY)
            val repo = repository()
            repo.answerRegisterWith(ENDPOINT)
            repo.setUp(ntfy("tk"), true)
            assertEquals(listOf(55L, null), kuma.saveCalls.map { it.first.id })
            assertEquals(100L, store.value.value?.kumaNotificationId)
        }

    @Test
    fun `tearDown deletes the Kuma notification, unregisters and clears everything`() =
        runTest {
            val repo = repository()
            repo.answerRegisterWith(ENDPOINT)
            repo.setUp(ntfy("tk"), true)

            repo.tearDown()

            assertEquals(listOf(100L), kuma.deleted)
            assertEquals(1, gateway.unregisterCount)
            assertNull(tokens.token)
            assertNull(store.value.value)
            assertEquals(PushSetupState.NotConfigured, repo.observeState().first())
        }

    @Test
    fun `tearDown ignores a notification that is already gone in Kuma`() =
        runTest {
            store.value.value = PushRegistration(ENDPOINT, 77L, NTFY)
            val repo = repository()
            repo.tearDown()
            assertNull(store.value.value)
            assertEquals(1, gateway.unregisterCount)
        }

    @Test
    fun `tearDown keeps the registration when Kuma is unreachable`() =
        runTest {
            store.value.value = PushRegistration(ENDPOINT, 77L, NTFY)
            kuma.failure = KumaException(KumaError(KumaError.Kind.NETWORK))
            val repo = repository()
            assertThrows<KumaException> { repo.tearDown() }
            assertEquals(77L, store.value.value?.kumaNotificationId)
            assertEquals(0, gateway.unregisterCount)
        }

    @Test
    fun `sendTest sends the current configuration`() =
        runTest {
            val repo = repository()
            repo.answerRegisterWith(ENDPOINT)
            repo.setUp(ntfy("tk"), true)

            repo.sendTest()

            val tested = kuma.tested.single()
            assertEquals(100L, tested.id)
            assertEquals(ENDPOINT, tested.fields["webhookURL"])
        }

    @Test
    fun `sendTest without setup fails with NOT_CONFIGURED`() =
        runTest {
            val e = assertThrows<PushSetupException> { repository().sendTest() }
            assertEquals(PushSetupException.Reason.NOT_CONFIGURED, e.reason)
        }

    @Test
    fun `changed endpoint for an existing notification schedules a Kuma update`() =
        runTest {
            store.value.value = PushRegistration(ENDPOINT, 100L, NTFY)
            val repo = repository()

            repo.handleNewEndpoint(ENDPOINT)
            assertEquals(0, scheduler.endpointUpdates)

            repo.handleNewEndpoint(ENDPOINT_2)
            assertEquals(1, scheduler.endpointUpdates)
            assertEquals(PushRegistration(ENDPOINT_2, 100L, NTFY), store.value.value)
        }

    @Test
    fun `endpoint without Kuma notification is only stored`() =
        runTest {
            gateway.saved = NTFY
            val repo = repository()
            repo.handleNewEndpoint(ENDPOINT)
            assertEquals(PushRegistration(ENDPOINT, null, NTFY), store.value.value)
            assertEquals(0, scheduler.endpointUpdates)
            assertEquals(PushSetupState.NotConfigured, repo.observeState().first())
        }

    @Test
    fun `updateKumaEndpoint saves the stored endpoint under the same id`() =
        runTest {
            val repo = repository()
            repo.answerRegisterWith(ENDPOINT)
            repo.setUp(ntfy("tk"), true)
            repo.handleNewEndpoint(ENDPOINT_2)

            repo.updateKumaEndpoint()

            val (notification, applyExisting) = kuma.saveCalls.last()
            assertEquals(100L, notification.id)
            assertEquals(ENDPOINT_2, notification.fields["webhookURL"])
            assertEquals(false, applyExisting)
        }

    @Test
    fun `unregistration by the distributor removes the Kuma notification`() =
        runTest {
            val repo = repository()
            repo.answerRegisterWith(ENDPOINT)
            repo.setUp(ntfy("tk"), true)

            repo.handleUnregistered()

            assertEquals(listOf(100L), kuma.deleted)
            assertNull(store.value.value)
        }

    @Test
    fun `state reflects missing distributor and refreshes on availableDistributors`() =
        runTest {
            gateway.installed = emptyList()
            val repo = repository()
            assertEquals(PushSetupState.NoDistributor, repo.observeState().first())
            gateway.installed = listOf(NTFY)
            assertEquals(listOf(NTFY), repo.availableDistributors())
            assertEquals(PushSetupState.NotConfigured, repo.observeState().first())
        }

    @Test
    fun `registration failure outside setup becomes an error state`() =
        runTest {
            val repo = repository()
            repo.handleRegistrationFailed("NETWORK")
            assertEquals(PushSetupState.Error("REGISTRATION_FAILED"), repo.observeState().first())
        }

    @Test
    fun `app start re-registers with the saved distributor`() =
        runTest {
            store.value.value = PushRegistration(ENDPOINT, 100L, NTFY)
            val repo = repository()
            repo.reRegisterIfConfigured()
            assertEquals(1, gateway.registerCount)
            assertEquals(NTFY, gateway.saved)
        }

    @Test
    fun `app start reports an uninstalled distributor`() =
        runTest {
            store.value.value = PushRegistration(ENDPOINT, 100L, OTHER)
            val repo = repository()
            repo.reRegisterIfConfigured()
            assertEquals(0, gateway.registerCount)
            assertEquals(PushSetupState.Error("NO_DISTRIBUTOR"), repo.observeState().first())
        }

    private companion object {
        const val ENDPOINT = "https://ntfy.example.com/upAbCdEfGhIjKl?up=1"
        const val ENDPOINT_2 = "https://ntfy.example.com/upZyXwVuTsRqPo?up=1"
    }
}
