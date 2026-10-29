package net.kirkanos.uptimekuma.push.fcm

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.kirkanos.uptimekuma.push.PushMessageHandler
import net.kirkanos.uptimekuma.push.data.PushRepositoryImpl
import net.kirkanos.uptimekuma.push.di.PushScope
import javax.inject.Inject

/**
 * Receives the relay's high-priority FCM data messages (`{"v": "1", "body": <Kuma webhook body>}`) and token
 * changes. Alerts take exactly the UnifiedPush path ([PushMessageHandler]): same parser, dedupe, mutes, quiet
 * hours, history and the expedited sync. Tokens and payloads are never logged.
 *
 * The default FirebaseApp is initialized by `PushInitializer` in `Application.onCreate`, which Android always runs
 * before creating this service.
 */
@AndroidEntryPoint
class KumaFirebaseMessagingService : FirebaseMessagingService() {
    @Inject lateinit var messageHandler: PushMessageHandler

    @Inject lateinit var repository: PushRepositoryImpl

    @Inject
    @PushScope
    lateinit var scope: CoroutineScope

    /** Runs on an FCM worker thread with a wake lock; the notification is posted before it returns. */
    override fun onMessageReceived(message: RemoteMessage) {
        messageHandler.handleRelayData(message.data)
    }

    override fun onNewToken(token: String) {
        Log.i(TAG, "New FCM registration token")
        scope.launch { repository.handleNewFcmToken(token) }
    }

    private companion object {
        const val TAG = "KumaFcmService"
    }
}
