package net.kirkanos.uptimekuma.push.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import net.kirkanos.uptimekuma.core.domain.KumaNotificationRepository
import net.kirkanos.uptimekuma.core.model.KumaError
import net.kirkanos.uptimekuma.core.model.KumaException
import net.kirkanos.uptimekuma.core.model.KumaNotification
import net.kirkanos.uptimekuma.core.model.PushRegistration
import net.kirkanos.uptimekuma.push.work.PushWorkScheduler

class FakeKumaNotificationRepository : KumaNotificationRepository {
    val stored = MutableStateFlow<Map<Long, KumaNotification>>(emptyMap())
    val saveCalls = mutableListOf<Pair<KumaNotification, Boolean>>()
    val deleted = mutableListOf<Long>()
    val tested = mutableListOf<KumaNotification>()
    var nextId = 100L
    var failure: KumaException? = null

    override fun observeNotifications(): Flow<List<KumaNotification>> = MutableStateFlow(stored.value.values.toList())

    override suspend fun save(
        notification: KumaNotification,
        applyExisting: Boolean,
    ): Long {
        failure?.let { throw it }
        saveCalls += notification to applyExisting
        val id = notification.id
        if (id != null && id !in stored.value) {
            throw KumaException(KumaError(KumaError.Kind.SERVER_REJECTED, "Cannot find notification"))
        }
        val newId = id ?: nextId++
        stored.value = stored.value + (newId to notification.copy(id = newId))
        return newId
    }

    override suspend fun delete(id: Long) {
        failure?.let { throw it }
        if (id !in stored.value) throw KumaException(KumaError(KumaError.Kind.SERVER_REJECTED, "not found"))
        deleted += id
        stored.value = stored.value - id
    }

    override suspend fun test(notification: KumaNotification) {
        failure?.let { throw it }
        tested += notification
    }
}

/** Simulates the distributor: [register] answers via [onRegister] (wire it to the repository's service event). */
class FakeDistributorGateway(
    var installed: List<String> = listOf(NTFY),
) : DistributorGateway {
    var saved: String? = null
    var default: String? = null
    var registerCount = 0
    var unregisterCount = 0
    var onRegister: () -> Unit = {}

    override fun distributors(): List<String> = installed

    override fun savedDistributor(): String? = saved

    override fun defaultDistributor(): String? = default

    override fun saveDistributor(packageName: String) {
        saved = packageName
    }

    override fun register(messageForDistributor: String?) {
        registerCount++
        onRegister()
    }

    override fun unregister() {
        unregisterCount++
    }

    override fun label(packageName: String): String = packageName.substringAfterLast('.')

    companion object {
        const val NTFY = "io.heckel.ntfy"
        const val OTHER = "org.example.distributor"
    }
}

class FakeRegistrationStore(
    initial: PushRegistration? = null,
) : PushRegistrationStore {
    val value = MutableStateFlow(initial)

    override fun observe(): Flow<PushRegistration?> = value

    override suspend fun update(transform: (PushRegistration?) -> PushRegistration?) {
        value.value = transform(value.value)
    }
}

class FakeTokenStore(
    var token: String? = null,
) : PushTokenStore {
    override fun load(): String? = token

    override fun save(token: String) {
        this.token = token
    }

    override fun clear() {
        token = null
    }
}

class FakeScheduler : PushWorkScheduler {
    var syncs = 0
    var endpointUpdates = 0

    override fun enqueueSync() {
        syncs++
    }

    override fun enqueueEndpointUpdate() {
        endpointUpdates++
    }
}
