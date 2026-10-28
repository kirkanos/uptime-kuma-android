package net.kirkanos.uptimekuma.core.domain

import kotlinx.coroutines.flow.Flow
import net.kirkanos.uptimekuma.core.model.PushRegistration

/** Push setup state, implemented in :push. Used by onboarding and settings screens. */
interface PushRepository {
    fun observeRegistration(): Flow<PushRegistration?>

    fun observeState(): Flow<PushSetupState>

    /** Installed UnifiedPush distributors (package names). Empty = user must install ntfy. */
    fun availableDistributors(): List<String>

    /**
     * Registers with [distributor] (or the default), waits for the endpoint and creates/updates
     * the Kuma webhook notification with [ntfyToken] as bearer token.
     */
    suspend fun setUp(
        ntfyToken: String,
        applyToAllMonitors: Boolean,
        distributor: String? = null,
    )

    /** Deletes the Kuma notification and unregisters from the distributor. */
    suspend fun tearDown()

    /** Triggers Kuma's test notification through this device's configuration. */
    suspend fun sendTest()
}

sealed interface PushSetupState {
    data object NoDistributor : PushSetupState

    data object NotConfigured : PushSetupState

    data object Registering : PushSetupState

    data object Active : PushSetupState

    data class Error(
        val message: String,
    ) : PushSetupState
}
