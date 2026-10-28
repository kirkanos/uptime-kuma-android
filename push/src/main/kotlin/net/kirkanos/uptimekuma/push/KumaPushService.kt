package net.kirkanos.uptimekuma.push

import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.kirkanos.uptimekuma.push.data.PushRepositoryImpl
import net.kirkanos.uptimekuma.push.data.PushServiceEvent
import net.kirkanos.uptimekuma.push.di.PushScope
import net.kirkanos.uptimekuma.push.notify.AlertNotifier
import net.kirkanos.uptimekuma.push.payload.PushPayloadParser
import net.kirkanos.uptimekuma.push.work.PushWorkScheduler
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage
import javax.inject.Inject

/**
 * Receives UnifiedPush events from the distributor (ntfy) via the connector's `MessagingReceiverImpl`.
 *
 * `onMessage` runs under the connector's wake lock: the notification is posted synchronously, the cache
 * refresh goes to WorkManager. Endpoint persistence and Kuma updates run in [scope] / WorkManager.
 * Endpoint URLs and payloads are never logged (the endpoint is the per-device secret).
 */
@AndroidEntryPoint
class KumaPushService : PushService() {
    @Inject lateinit var repository: PushRepositoryImpl

    @Inject lateinit var notifier: AlertNotifier

    @Inject lateinit var scheduler: PushWorkScheduler

    @Inject
    @PushScope
    lateinit var scope: CoroutineScope

    override fun onNewEndpoint(
        endpoint: PushEndpoint,
        instance: String,
    ) {
        Log.i(TAG, "New UnifiedPush endpoint (instance $instance)")
        repository.onServiceEvent(PushServiceEvent.NewEndpoint(endpoint.url))
        scope.launch { repository.handleNewEndpoint(endpoint.url) }
    }

    override fun onMessage(
        message: PushMessage,
        instance: String,
    ) {
        val event = PushPayloadParser.parse(message.content)
        try {
            notifier.show(event)
        } catch (e: RuntimeException) {
            Log.e(TAG, "Showing push alert failed", e)
        }
        if (event.monitorId != null) scheduler.enqueueSync()
    }

    override fun onRegistrationFailed(
        reason: FailedReason,
        instance: String,
    ) {
        Log.w(TAG, "UnifiedPush registration failed: $reason")
        repository.onServiceEvent(PushServiceEvent.RegistrationFailed(reason.name))
        repository.handleRegistrationFailed(reason.name)
    }

    override fun onUnregistered(instance: String) {
        Log.i(TAG, "UnifiedPush unregistered (instance $instance)")
        repository.onServiceEvent(PushServiceEvent.Unregistered)
        scope.launch { repository.handleUnregistered() }
    }

    override fun onTempUnavailable(instance: String) {
        Log.i(TAG, "UnifiedPush distributor temporarily unavailable")
        repository.onServiceEvent(PushServiceEvent.TempUnavailable)
    }

    private companion object {
        const val TAG = "KumaPushService"
    }
}
