package net.kirkanos.uptimekuma.push

import android.util.Log
import net.kirkanos.uptimekuma.core.model.PushEvent
import net.kirkanos.uptimekuma.push.payload.PushPayloadParser
import net.kirkanos.uptimekuma.push.work.PushWorkScheduler
import javax.inject.Inject
import javax.inject.Singleton

/** Posts a parsed alert (dedupe, mutes, quiet hours, history). Implemented by `AlertNotifier`. */
fun interface AlertPresenter {
    /** Returns `false` if the event was dropped (duplicate, suppressed) or could not be posted. */
    fun show(event: PushEvent): Boolean
}

/**
 * The one path every incoming push takes, whatever the transport: UnifiedPush (`KumaPushService.onMessage`)
 * and FCM (`KumaFirebaseMessagingService.onMessageReceived` in `:push-fcm`). Parses the webhook body with
 * [PushPayloadParser], posts it through [AlertPresenter] and enqueues the expedited cache sync.
 *
 * Runs synchronously on the caller's thread (both services hold a wake lock while it runs). Payloads are never
 * logged.
 */
@Singleton
class PushMessageHandler
    @Inject
    constructor(
        private val presenter: AlertPresenter,
        private val scheduler: PushWorkScheduler,
    ) {
        /** A raw webhook body as delivered by UnifiedPush. */
        fun handle(body: ByteArray): PushEvent = handle(body.decodeToString())

        fun handle(body: String): PushEvent {
            val event = PushPayloadParser.parse(body)
            try {
                presenter.show(event)
            } catch (e: RuntimeException) {
                Log.e(TAG, "Showing push alert failed", e)
            }
            if (event.monitorId != null) scheduler.enqueueSync()
            return event
        }

        /**
         * An FCM data message from the relay: `{"v": "1", "body": "<raw webhook body>"}`. Returns `null` (and
         * shows nothing) if there is no body. Unknown versions are still tried: the body is Kuma's webhook body,
         * which the parser handles leniently.
         */
        fun handleRelayData(data: Map<String, String>): PushEvent? {
            val body = data[KEY_BODY]
            if (body == null) {
                Log.w(TAG, "FCM message without body (keys: ${data.keys})")
                return null
            }
            if (data[KEY_VERSION] != RELAY_VERSION) Log.w(TAG, "Unknown relay message version ${data[KEY_VERSION]}")
            return handle(body)
        }

        companion object {
            const val KEY_VERSION: String = "v"
            const val KEY_BODY: String = "body"
            const val RELAY_VERSION: String = "1"
            private const val TAG = "PushMessageHandler"
        }
    }
