package net.kirkanos.uptimekuma.core.testing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import net.kirkanos.uptimekuma.core.domain.KumaNotificationRepository
import net.kirkanos.uptimekuma.core.domain.PushRepository
import net.kirkanos.uptimekuma.core.domain.PushSetupState
import net.kirkanos.uptimekuma.core.model.KumaException
import net.kirkanos.uptimekuma.core.model.KumaNotification
import net.kirkanos.uptimekuma.core.model.PushRegistration

/** In-memory [KumaNotificationRepository]; [save] assigns increasing ids to new notifications. */
class FakeKumaNotificationRepository(
    initial: List<KumaNotification> = emptyList(),
) : KumaNotificationRepository {
    val notifications = MutableStateFlow(initial)
    var error: KumaException? = null
    val tested = mutableListOf<KumaNotification>()
    private var nextId = (initial.mapNotNull { it.id }.maxOrNull() ?: 0L) + 1

    override fun observeNotifications(): Flow<List<KumaNotification>> = notifications

    override suspend fun save(
        notification: KumaNotification,
        applyExisting: Boolean,
    ): Long {
        error?.let { throw it }
        val id = notification.id ?: nextId++
        val saved = notification.copy(id = id)
        notifications.value = notifications.value.filterNot { it.id == id } + saved
        return id
    }

    override suspend fun delete(id: Long) {
        error?.let { throw it }
        notifications.value = notifications.value.filterNot { it.id == id }
    }

    override suspend fun test(notification: KumaNotification) {
        error?.let { throw it }
        tested += notification
    }
}

/** In-memory [PushRepository]; [setUp] registers immediately with a fake endpoint. */
class FakePushRepository(
    var distributors: List<String> = listOf("io.heckel.ntfy"),
    initialRegistration: PushRegistration? = null,
) : PushRepository {
    val registration = MutableStateFlow(initialRegistration)
    val state =
        MutableStateFlow<PushSetupState>(
            when {
                initialRegistration != null -> PushSetupState.Active
                distributors.isEmpty() -> PushSetupState.NoDistributor
                else -> PushSetupState.NotConfigured
            },
        )
    var error: KumaException? = null
    var testCount = 0
        private set

    override fun observeRegistration(): Flow<PushRegistration?> = registration

    override fun observeState(): Flow<PushSetupState> = state

    override fun availableDistributors(): List<String> = distributors

    override suspend fun setUp(
        ntfyToken: String,
        applyToAllMonitors: Boolean,
        distributor: String?,
    ) {
        error?.let {
            state.value = PushSetupState.Error(it.message.orEmpty())
            throw it
        }
        registration.value =
            PushRegistration(
                endpoint = "https://ntfy.example.org/upFAKE?up=1",
                kumaNotificationId = 1,
                distributor = distributor ?: distributors.firstOrNull(),
            )
        state.value = PushSetupState.Active
    }

    override suspend fun tearDown() {
        registration.value = null
        state.value = if (distributors.isEmpty()) PushSetupState.NoDistributor else PushSetupState.NotConfigured
    }

    override suspend fun sendTest() {
        error?.let { throw it }
        testCount++
    }
}
