package net.kirkanos.uptimekuma.push.data

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.kirkanos.uptimekuma.core.domain.KumaNotificationRepository
import net.kirkanos.uptimekuma.core.domain.PushRepository
import net.kirkanos.uptimekuma.core.domain.PushSetupState
import net.kirkanos.uptimekuma.core.model.KumaError
import net.kirkanos.uptimekuma.core.model.KumaException
import net.kirkanos.uptimekuma.core.model.PushRegistration
import net.kirkanos.uptimekuma.push.di.PushIoDispatcher
import net.kirkanos.uptimekuma.push.payload.KumaWebhookNotification
import net.kirkanos.uptimekuma.push.work.PushWorkScheduler
import javax.inject.Inject
import javax.inject.Singleton

/** Name shown for this device in Kuma's notification list. */
fun interface DeviceNameProvider {
    fun deviceName(): String
}

/** UnifiedPush events as delivered to `KumaPushService`. */
sealed interface PushServiceEvent {
    data class NewEndpoint(
        val url: String,
    ) : PushServiceEvent

    data class RegistrationFailed(
        val reason: String,
    ) : PushServiceEvent

    data object Unregistered : PushServiceEvent

    data object TempUnavailable : PushServiceEvent
}

/**
 * Push setup and lifecycle: distributor registration, Kuma webhook notification, persisted state.
 *
 * `KumaPushService` forwards every UnifiedPush event to [onServiceEvent] (synchronously, so that a
 * pending [setUp] sees it) and then calls the matching suspend handler in a background scope.
 *
 * Suspend functions throw [KumaException] (Kuma side) or [PushSetupException] (device side).
 */
@Singleton
class PushRepositoryImpl
    @Inject
    constructor(
        private val notifications: KumaNotificationRepository,
        private val gateway: DistributorGateway,
        private val registrationStore: PushRegistrationStore,
        private val tokenStore: PushTokenStore,
        private val scheduler: PushWorkScheduler,
        private val deviceNameProvider: DeviceNameProvider,
        @PushIoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : PushRepository {
        /** Last UnifiedPush event with a sequence number, so a waiter only reacts to events after it started. */
        private data class Signal(
            val seq: Long,
            val event: PushServiceEvent?,
        )

        private val signals = MutableStateFlow(Signal(0, null))
        private val transientState = MutableStateFlow<PushSetupState?>(null)
        private val distributors = MutableStateFlow<List<String>?>(null)
        private val mutex = Mutex()

        @Volatile
        private var setupInProgress = false

        override fun observeRegistration(): Flow<PushRegistration?> = registrationStore.observe()

        override fun observeState(): Flow<PushSetupState> =
            combine(
                registrationStore.observe(),
                transientState,
                distributors.onStart { if (distributors.value == null) refreshDistributors() }.filterNotNull(),
            ) { registration, transient, installed ->
                transient ?: when {
                    registration?.kumaNotificationId != null -> PushSetupState.Active
                    installed.isEmpty() -> PushSetupState.NoDistributor
                    else -> PushSetupState.NotConfigured
                }
            }

        /** Also refreshes [observeState] (e.g. after the user installed ntfy and came back). */
        override fun availableDistributors(): List<String> = refreshDistributors()

        /** Human readable distributor name for pickers. */
        fun distributorLabel(packageName: String): String = gateway.label(packageName)

        /** Distributor that [setUp] would pick without an explicit choice, if unambiguous. */
        fun preselectedDistributor(): String? = chooseDistributor(null, allowAmbiguous = false)

        /** The stored ntfy token (decrypted), to prefill the setup form. */
        suspend fun storedToken(): String? = withContext(ioDispatcher) { tokenStore.load() }

        override suspend fun setUp(
            ntfyToken: String,
            applyToAllMonitors: Boolean,
            distributor: String?,
        ) {
            val token = ntfyToken.trim()
            if (token.isEmpty()) throw PushSetupException(PushSetupException.Reason.MISSING_TOKEN)
            mutex.withLock {
                setupInProgress = true
                transientState.value = PushSetupState.Registering
                try {
                    performSetUp(token, applyToAllMonitors, distributor)
                    transientState.value = null
                } catch (e: CancellationException) {
                    transientState.value = null
                    throw e
                } catch (e: Exception) {
                    transientState.value = PushSetupState.Error(PushErrorCodes.of(e))
                    throw e
                } finally {
                    setupInProgress = false
                }
            }
        }

        private suspend fun performSetUp(
            token: String,
            applyToAllMonitors: Boolean,
            requestedDistributor: String?,
        ) {
            val distributor =
                withContext(ioDispatcher) { chooseDistributor(requestedDistributor, allowAmbiguous = true) }
                    ?: throw PushSetupException(PushSetupException.Reason.NO_DISTRIBUTOR)
            withContext(ioDispatcher) { tokenStore.save(token) }

            val start = signals.value.seq
            withContext(ioDispatcher) {
                gateway.saveDistributor(distributor)
                gateway.register(null)
            }
            val endpoint = awaitEndpoint(start)
            registrationStore.update { current ->
                PushRegistration(endpoint, current?.kumaNotificationId, distributor)
            }

            val existingId = registrationStore.get()?.kumaNotificationId
            val id = saveKumaNotification(endpoint, token, existingId, applyToAllMonitors)
            registrationStore.update { PushRegistration(endpoint, id, distributor) }
        }

        /** Updates [existingId]; if Kuma no longer knows it (deleted in the web UI) a new one is created. */
        private suspend fun saveKumaNotification(
            endpoint: String,
            token: String,
            existingId: Long?,
            applyExisting: Boolean,
        ): Long {
            val name = deviceNameProvider.deviceName()
            return try {
                notifications.save(KumaWebhookNotification.build(endpoint, token, name, existingId), applyExisting)
            } catch (e: KumaException) {
                if (existingId == null || e.error.kind != KumaError.Kind.SERVER_REJECTED) throw e
                Log.i(TAG, "Kuma notification $existingId is gone; creating a new one")
                notifications.save(KumaWebhookNotification.build(endpoint, token, name, null), applyExisting)
            }
        }

        private suspend fun awaitEndpoint(start: Long): String {
            val signal =
                withTimeoutOrNull(ENDPOINT_TIMEOUT_MILLIS) {
                    signals.first { it.seq > start && it.event.answersRegistration() }
                } ?: throw PushSetupException(PushSetupException.Reason.ENDPOINT_TIMEOUT)
            return when (val event = signal.event) {
                is PushServiceEvent.NewEndpoint -> {
                    event.url
                }

                is PushServiceEvent.RegistrationFailed -> {
                    throw PushSetupException(
                        if (event.reason == REASON_ACTION_REQUIRED) {
                            PushSetupException.Reason.ACTION_REQUIRED
                        } else {
                            PushSetupException.Reason.REGISTRATION_FAILED
                        },
                        event.reason,
                    )
                }

                else -> {
                    error("unreachable")
                }
            }
        }

        override suspend fun tearDown() {
            mutex.withLock {
                val registration = registrationStore.get()
                registration?.kumaNotificationId?.let { id ->
                    try {
                        notifications.delete(id)
                    } catch (e: KumaException) {
                        // Already deleted in Kuma: fine. Anything else (offline, logged out) must be retried.
                        if (e.error.kind != KumaError.Kind.SERVER_REJECTED) throw e
                    }
                }
                withContext(ioDispatcher) {
                    runCatching { gateway.unregister() }.onFailure { Log.w(TAG, "unregister failed", it) }
                    tokenStore.clear()
                }
                registrationStore.clear()
                transientState.value = null
            }
        }

        override suspend fun sendTest() {
            val registration =
                registrationStore.get()?.takeIf { it.kumaNotificationId != null }
                    ?: throw PushSetupException(PushSetupException.Reason.NOT_CONFIGURED)
            val token =
                withContext(ioDispatcher) { tokenStore.load() }
                    ?: throw PushSetupException(PushSetupException.Reason.NOT_CONFIGURED, "token missing")
            notifications.test(
                KumaWebhookNotification.build(
                    endpoint = registration.endpoint,
                    ntfyToken = token,
                    deviceName = deviceNameProvider.deviceName(),
                    id = registration.kumaNotificationId,
                ),
            )
        }

        /** Clears a shown error (e.g. when the user starts over). */
        fun clearError() {
            transientState.update { if (it is PushSetupState.Error) null else it }
        }

        // --- Called by KumaPushService ---------------------------------------------------------

        /** Synchronous: records the event for a pending [setUp]. */
        fun onServiceEvent(event: PushServiceEvent) {
            signals.update { Signal(it.seq + 1, event) }
        }

        /** Persists the endpoint; if it changed for an existing Kuma notification, schedules the update there. */
        suspend fun handleNewEndpoint(url: String) {
            var changedForExisting = false
            registrationStore.update { current ->
                changedForExisting = current?.kumaNotificationId != null && current.endpoint != url
                current?.copy(endpoint = url) ?: PushRegistration(endpoint = url, distributor = savedDistributor())
            }
            if (changedForExisting && !setupInProgress) scheduler.enqueueEndpointUpdate()
            if (!setupInProgress) clearError()
        }

        /** The distributor dropped us (ntfy uninstalled, subscription removed, UnifiedPush disabled). */
        suspend fun handleUnregistered() {
            if (setupInProgress) return // switching distributors unregisters the old one; setUp handles it
            mutex.withLock {
                val registration = registrationStore.get() ?: return
                registration.kumaNotificationId?.let { id ->
                    try {
                        notifications.delete(id)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not delete Kuma notification $id after unregistration", e)
                    }
                }
                registrationStore.clear()
                refreshDistributors()
            }
        }

        fun handleRegistrationFailed(reason: String) {
            if (setupInProgress) return
            transientState.value =
                PushSetupState.Error(
                    if (reason == REASON_ACTION_REQUIRED) {
                        PushSetupException.Reason.ACTION_REQUIRED.name
                    } else {
                        PushSetupException.Reason.REGISTRATION_FAILED.name
                    },
                )
        }

        /** App start: refresh the distributor registration so endpoint changes are noticed. */
        suspend fun reRegisterIfConfigured() {
            val registration = registrationStore.get() ?: return
            if (registration.kumaNotificationId == null) return
            val registered =
                withContext(ioDispatcher) {
                    val installed = refreshDistributors()
                    val distributor =
                        registration.distributor?.takeIf { it in installed }
                            ?: return@withContext false
                    if (savedDistributor() != distributor) gateway.saveDistributor(distributor)
                    gateway.register(null)
                    true
                }
            if (!registered) {
                Log.w(TAG, "Saved distributor is no longer installed")
                transientState.value = PushSetupState.Error(PushSetupException.Reason.NO_DISTRIBUTOR.name)
            }
        }

        /** Used by `EndpointUpdateWorker`:writes the stored endpoint into the existing Kuma notification. */
        suspend fun updateKumaEndpoint() {
            mutex.withLock {
                val registration = registrationStore.get() ?: return
                val id = registration.kumaNotificationId ?: return
                val token =
                    withContext(ioDispatcher) { tokenStore.load() }
                        ?: throw PushSetupException(PushSetupException.Reason.NOT_CONFIGURED, "token missing")
                val newId = saveKumaNotification(registration.endpoint, token, id, applyExisting = false)
                if (newId != id) registrationStore.update { it?.copy(kumaNotificationId = newId) }
            }
        }

        private fun refreshDistributors(): List<String> =
            runCatching { gateway.distributors() }
                .getOrElse { emptyList() }
                .also { distributors.value = it }

        private fun PushServiceEvent?.answersRegistration(): Boolean =
            this is PushServiceEvent.NewEndpoint || this is PushServiceEvent.RegistrationFailed

        private fun savedDistributor(): String? = runCatching { gateway.savedDistributor() }.getOrNull()

        private fun chooseDistributor(
            requested: String?,
            allowAmbiguous: Boolean,
        ): String? {
            val installed = refreshDistributors()
            return requested?.takeIf { it in installed }
                ?: savedDistributor()?.takeIf { it in installed }
                ?: installed.singleOrNull()
                ?: runCatching { gateway.defaultDistributor() }.getOrNull()?.takeIf { it in installed }
                ?: installed.firstOrNull()?.takeIf { allowAmbiguous }
        }

        internal companion object {
            const val TAG = "PushRepository"
            const val ENDPOINT_TIMEOUT_MILLIS = 20_000L
            const val REASON_ACTION_REQUIRED = "ACTION_REQUIRED"
        }
    }
