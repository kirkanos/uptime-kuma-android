package net.kirkanos.uptimekuma.core.domain

import kotlinx.coroutines.flow.Flow
import net.kirkanos.uptimekuma.core.model.FirebaseConfig
import net.kirkanos.uptimekuma.core.model.PushRegistration
import net.kirkanos.uptimekuma.core.model.PushTransport

/** Push setup state, implemented in :push. Used by onboarding and settings screens. */
interface PushRepository {
    fun observeRegistration(): Flow<PushRegistration?>

    fun observeState(): Flow<PushSetupState>

    /** Transport of the active setup (a Kuma notification exists), `null` while push is not set up. */
    fun observeTransport(): Flow<PushTransport?>

    /** Transports this build supports: always [PushTransport.NTFY], [PushTransport.FIREBASE] only in `full`. */
    fun availableTransports(): Set<PushTransport>

    /** Installed UnifiedPush distributors (package names). Empty = user must install ntfy. */
    fun availableDistributors(): List<String>

    /**
     * Registers this device with the transport of [config], creates or updates its Kuma webhook notification
     * and, when the other transport was active, tears that one down afterwards (its Kuma notification is
     * deleted, UnifiedPush unregistered or the FCM token deleted).
     *
     * @param applyToAllMonitors attach the notification to every monitor and make it Kuma's default.
     */
    suspend fun setUp(
        config: PushTransportConfig,
        applyToAllMonitors: Boolean,
    )

    /** Deletes the Kuma notification and unregisters the active transport. */
    suspend fun tearDown()

    /** Triggers Kuma's test notification through this device's configuration. */
    suspend fun sendTest()
}

/** What [PushRepository.setUp] needs per transport. */
sealed interface PushTransportConfig {
    val transport: PushTransport

    /**
     * @param token access token of the ntfy user allowed to publish to `up*` topics (bearer token in Kuma).
     * @param distributor UnifiedPush distributor package; `null` = saved / single / default one.
     */
    data class Ntfy(
        val token: String,
        val distributor: String? = null,
    ) : PushTransportConfig {
        override val transport: PushTransport get() = PushTransport.NTFY

        override fun toString(): String = "Ntfy(token=***, distributor=$distributor)"
    }

    /**
     * @param relayUrl base URL of the user's FCM relay; Kuma posts to `<relayUrl>/v1/fcm/<FCM token>`.
     * @param relayToken bearer token the relay expects (`RELAY_TOKEN`).
     * @param firebase public values of the user's own Firebase project.
     */
    data class Firebase(
        val relayUrl: String,
        val relayToken: String,
        val firebase: FirebaseConfig,
    ) : PushTransportConfig {
        override val transport: PushTransport get() = PushTransport.FIREBASE

        override fun toString(): String = "Firebase(relayUrl=$relayUrl, relayToken=***, firebase=$firebase)"
    }
}

sealed interface PushSetupState {
    /** No UnifiedPush distributor installed and no other transport available in this build. */
    data object NoDistributor : PushSetupState

    data object NotConfigured : PushSetupState

    data object Registering : PushSetupState

    data object Active : PushSetupState

    data class Error(
        val message: String,
    ) : PushSetupState
}
