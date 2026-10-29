package net.kirkanos.uptimekuma.push.data

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.kirkanos.uptimekuma.core.domain.KumaNotificationRepository
import net.kirkanos.uptimekuma.core.domain.PushRepository
import net.kirkanos.uptimekuma.core.domain.PushSetupState
import net.kirkanos.uptimekuma.core.domain.PushTransportConfig
import net.kirkanos.uptimekuma.core.model.KumaError
import net.kirkanos.uptimekuma.core.model.KumaException
import net.kirkanos.uptimekuma.core.model.PushRegistration
import net.kirkanos.uptimekuma.core.model.PushTransport
import net.kirkanos.uptimekuma.push.di.PushIoDispatcher
import net.kirkanos.uptimekuma.push.di.RelayTokenStore
import net.kirkanos.uptimekuma.push.firebase.FirebaseInitResult
import net.kirkanos.uptimekuma.push.firebase.FirebaseSettings
import net.kirkanos.uptimekuma.push.firebase.FirebaseSettingsStore
import net.kirkanos.uptimekuma.push.firebase.FirebaseTransport
import net.kirkanos.uptimekuma.push.firebase.RelayUrl
import net.kirkanos.uptimekuma.push.payload.KumaWebhookNotification
import net.kirkanos.uptimekuma.push.work.PushWorkScheduler
import java.util.Optional
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
 * Push setup and lifecycle for both transports: UnifiedPush/ntfy (distributor registration) and Firebase
 * (FCM token via [FirebaseTransport], relay URL), the Kuma webhook notification and the persisted state.
 * Only one transport is active at a time; setting up the other one tears the previous one down afterwards.
 *
 * `KumaPushService` forwards every UnifiedPush event to [onServiceEvent] (synchronously, so that a
 * pending [setUp] sees it) and then calls the matching suspend handler in a background scope. The FCM service
 * of `:push-fcm` calls [handleNewFcmToken].
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
        private val firebase: Optional<FirebaseTransport>,
        private val firebaseSettings: FirebaseSettingsStore,
        @RelayTokenStore private val relayTokenStore: PushTokenStore,
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
                    installed.isEmpty() && !firebase.isPresent -> PushSetupState.NoDistributor
                    else -> PushSetupState.NotConfigured
                }
            }

        override fun observeTransport(): Flow<PushTransport?> =
            registrationStore
                .observe()
                .map { registration -> registration?.takeIf { it.kumaNotificationId != null }?.transport }
                .distinctUntilChanged()

        override fun availableTransports(): Set<PushTransport> =
            if (firebase.isPresent) setOf(PushTransport.NTFY, PushTransport.FIREBASE) else setOf(PushTransport.NTFY)

        /** Also refreshes [observeState] (e.g. after the user installed ntfy and came back). */
        override fun availableDistributors(): List<String> = refreshDistributors()

        /** Human readable distributor name for pickers. */
        fun distributorLabel(packageName: String): String = gateway.label(packageName)

        /** Distributor that [setUp] would pick without an explicit choice, if unambiguous. */
        fun preselectedDistributor(): String? = chooseDistributor(null, allowAmbiguous = false)

        /** The stored ntfy token (decrypted), to prefill the setup form. */
        suspend fun storedToken(): String? = withContext(ioDispatcher) { tokenStore.load() }

        override suspend fun setUp(
            config: PushTransportConfig,
            applyToAllMonitors: Boolean,
        ) {
            when (config) {
                is PushTransportConfig.Ntfy -> {
                    val token = config.token.trim()
                    if (token.isEmpty()) throw PushSetupException(PushSetupException.Reason.MISSING_TOKEN)
                    guardedSetUp { performNtfySetUp(token, applyToAllMonitors, config.distributor) }
                }

                is PushTransportConfig.Firebase -> {
                    guardedSetUp { performFirebaseSetUp(config, applyToAllMonitors) }
                }
            }
        }

        private suspend fun guardedSetUp(block: suspend () -> Unit) {
            mutex.withLock {
                setupInProgress = true
                transientState.value = PushSetupState.Registering
                try {
                    block()
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

        private suspend fun performNtfySetUp(
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
            val previous = registrationStore.get()
            val switching = previous != null && previous.transport != PushTransport.NTFY
            if (!switching) {
                registrationStore.update { current ->
                    PushRegistration(endpoint, current?.kumaNotificationId, distributor)
                }
            }

            val existingId = if (switching) null else registrationStore.get()?.kumaNotificationId
            // "All monitors" also means future ones: mark it as Kuma's default notification.
            val id =
                saveKumaNotification(
                    PushTransport.NTFY,
                    endpoint,
                    token,
                    existingId,
                    applyToAllMonitors,
                    isDefault = applyToAllMonitors,
                )
            registrationStore.update { PushRegistration(endpoint, id, distributor) }
            if (switching) tearDownPrevious(checkNotNull(previous))
        }

        private suspend fun performFirebaseSetUp(
            config: PushTransportConfig.Firebase,
            applyToAllMonitors: Boolean,
        ) {
            val transport =
                firebase.orElse(null) ?: throw PushSetupException(PushSetupException.Reason.FIREBASE_UNAVAILABLE)
            val (relayUrl, relayToken, firebaseConfig) = validated(config)

            val initResult =
                withContext(ioDispatcher) {
                    // Saved before initializing: after a restart the new values are the ones Firebase starts with.
                    firebaseSettings.save(FirebaseSettings(relayUrl, firebaseConfig))
                    relayTokenStore.save(relayToken)
                    transport.initialize(firebaseConfig)
                }
            if (initResult == FirebaseInitResult.RESTART_REQUIRED) {
                throw PushSetupException(PushSetupException.Reason.FIREBASE_RESTART_REQUIRED)
            }
            val fcmToken = transport.fetchToken()
            val webhookUrl = RelayUrl.webhookUrl(relayUrl, fcmToken)

            val previous = registrationStore.get()
            val switching = previous != null && previous.transport != PushTransport.FIREBASE
            val existingId = if (switching) null else previous?.kumaNotificationId
            val id =
                saveKumaNotification(
                    PushTransport.FIREBASE,
                    webhookUrl,
                    relayToken,
                    existingId,
                    applyToAllMonitors,
                    isDefault = applyToAllMonitors,
                )
            registrationStore.update {
                PushRegistration(webhookUrl, id, distributor = null, transport = PushTransport.FIREBASE)
            }
            // From now on token rotations must reach onNewToken (periodic validation by the SDK).
            withContext(ioDispatcher) { transport.setAutoInitEnabled(true) }
            if (switching) tearDownPrevious(checkNotNull(previous))
        }

        /** Normalized relay URL, trimmed relay token and Firebase values, or the first input error. */
        private fun validated(config: PushTransportConfig.Firebase): PushTransportConfig.Firebase {
            val relayUrl = RelayUrl.normalize(config.relayUrl)
            val relayToken = config.relayToken.trim()
            val firebaseConfig = config.firebase.trimmed()
            val invalid = firebaseConfig.invalidFields()
            val error =
                when {
                    relayUrl == null -> {
                        PushSetupException(PushSetupException.Reason.RELAY_URL_INVALID)
                    }

                    relayToken.isEmpty() -> {
                        PushSetupException(PushSetupException.Reason.MISSING_RELAY_TOKEN)
                    }

                    invalid.isNotEmpty() -> {
                        PushSetupException(
                            PushSetupException.Reason.FIREBASE_CONFIG_INVALID,
                            invalid.joinToString(",") { it.name },
                        )
                    }

                    else -> {
                        null
                    }
                }
            if (error != null) throw error
            return PushTransportConfig.Firebase(checkNotNull(relayUrl), relayToken, firebaseConfig)
        }

        /**
         * After switching transports: removes the previous transport's Kuma notification and device registration.
         * Best effort: the new transport already works, so failures are only logged.
         */
        private suspend fun tearDownPrevious(previous: PushRegistration) {
            previous.kumaNotificationId?.let { id ->
                try {
                    notifications.delete(id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Could not delete the previous Kuma notification $id", e)
                }
            }
            tearDownDevice(previous.transport)
        }

        /** Device side of a teardown; never throws (except cancellation). */
        private suspend fun tearDownDevice(transport: PushTransport) {
            when (transport) {
                PushTransport.NTFY -> {
                    withContext(ioDispatcher) {
                        runCatching { gateway.unregister() }.onFailure { Log.w(TAG, "unregister failed", it) }
                        tokenStore.clear()
                    }
                }

                PushTransport.FIREBASE -> {
                    firebase.orElse(null)?.deleteToken()
                    withContext(ioDispatcher) { relayTokenStore.clear() }
                }
            }
        }

        /** Updates [existingId]; if Kuma no longer knows it (deleted in the web UI) a new one is created. */
        private suspend fun saveKumaNotification(
            transport: PushTransport,
            url: String,
            token: String,
            existingId: Long?,
            applyExisting: Boolean,
            isDefault: Boolean,
        ): Long {
            val name = deviceNameProvider.deviceName()
            return try {
                notifications.save(
                    KumaWebhookNotification.build(transport, url, token, name, existingId, isDefault),
                    applyExisting,
                )
            } catch (e: KumaException) {
                if (existingId == null || e.error.kind != KumaError.Kind.SERVER_REJECTED) throw e
                Log.i(TAG, "Kuma notification $existingId is gone; creating a new one")
                notifications.save(
                    KumaWebhookNotification.build(transport, url, token, name, null, isDefault),
                    applyExisting,
                )
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
                tearDownDevice(registration?.transport ?: PushTransport.NTFY)
                registrationStore.clear()
                transientState.value = null
            }
        }

        override suspend fun sendTest() {
            val registration =
                registrationStore.get()?.takeIf { it.kumaNotificationId != null }
                    ?: throw PushSetupException(PushSetupException.Reason.NOT_CONFIGURED)
            val token =
                withContext(ioDispatcher) { tokenStoreFor(registration.transport).load() }
                    ?: throw PushSetupException(PushSetupException.Reason.NOT_CONFIGURED, "token missing")
            notifications.test(
                KumaWebhookNotification.build(
                    transport = registration.transport,
                    url = registration.endpoint,
                    token = token,
                    deviceName = deviceNameProvider.deviceName(),
                    id = registration.kumaNotificationId,
                    isDefault = false,
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
                // A late UnifiedPush endpoint while Firebase is active must not overwrite the relay URL.
                if (current != null && current.transport != PushTransport.NTFY) return@update current
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
                if (registration.transport != PushTransport.NTFY) return // leftover of a switch to Firebase
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

        /** App start: refresh the distributor registration so endpoint changes are noticed (ntfy only). */
        suspend fun reRegisterIfConfigured() {
            val registration = registrationStore.get() ?: return
            if (registration.kumaNotificationId == null || registration.transport != PushTransport.NTFY) return
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

        // --- Called by the FCM service of :push-fcm -----------------------------------------------

        /**
         * FCM issued a new registration token (rotation, app data restore, reinstall of Play services). If the
         * Firebase transport is active, the webhook URL in Kuma must follow: stored here, pushed to Kuma by
         * `EndpointUpdateWorker` (retried until online).
         */
        suspend fun handleNewFcmToken(token: String) {
            val relayUrl = withContext(ioDispatcher) { firebaseSettings.load()?.relayUrl } ?: return
            val url = RelayUrl.webhookUrl(relayUrl, token)
            var changedForExisting = false
            registrationStore.update { current ->
                if (current?.transport != PushTransport.FIREBASE || current.kumaNotificationId == null) {
                    return@update current
                }
                changedForExisting = current.endpoint != url
                current.copy(endpoint = url)
            }
            // During setUp the token is fetched directly and written to Kuma there.
            if (changedForExisting && !setupInProgress) scheduler.enqueueEndpointUpdate()
        }

        /** Used by `EndpointUpdateWorker`: writes the stored endpoint into the existing Kuma notification. */
        suspend fun updateKumaEndpoint() {
            mutex.withLock {
                val registration = registrationStore.get() ?: return
                val id = registration.kumaNotificationId ?: return
                val token =
                    withContext(ioDispatcher) { tokenStoreFor(registration.transport).load() }
                        ?: throw PushSetupException(PushSetupException.Reason.NOT_CONFIGURED, "token missing")
                // Keep the default flag the user chose at setup time (read back from Kuma's list).
                val wasDefault =
                    notifications
                        .observeNotifications()
                        .first()
                        .firstOrNull { it.id == id }
                        ?.isDefault ?: false
                val newId =
                    saveKumaNotification(
                        registration.transport,
                        registration.endpoint,
                        token,
                        id,
                        applyExisting = false,
                        isDefault = wasDefault,
                    )
                if (newId != id) registrationStore.update { it?.copy(kumaNotificationId = newId) }
            }
        }

        private fun tokenStoreFor(transport: PushTransport): PushTokenStore =
            when (transport) {
                PushTransport.NTFY -> tokenStore
                PushTransport.FIREBASE -> relayTokenStore
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
