package net.kirkanos.uptimekuma.push

import android.util.Log
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/**
 * Receives UnifiedPush events (endpoint changes and incoming messages from the user's ntfy server).
 * Placeholder: logs only. Real handling (notification channels, WorkManager sync) follows.
 */
class KumaPushService : PushService() {
    override fun onNewEndpoint(
        endpoint: PushEndpoint,
        instance: String,
    ) {
        Log.i(TAG, "New UnifiedPush endpoint for instance $instance")
    }

    override fun onMessage(
        message: PushMessage,
        instance: String,
    ) {
        Log.i(TAG, "Push message for instance $instance (${message.content.size} bytes)")
    }

    override fun onRegistrationFailed(
        reason: FailedReason,
        instance: String,
    ) {
        Log.w(TAG, "UnifiedPush registration failed for $instance: $reason")
    }

    override fun onUnregistered(instance: String) {
        Log.i(TAG, "UnifiedPush unregistered: $instance")
    }

    private companion object {
        const val TAG = "KumaPushService"
    }
}
