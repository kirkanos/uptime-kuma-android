package net.kirkanos.uptimekuma.push

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.kirkanos.uptimekuma.push.data.PushRepositoryImpl
import net.kirkanos.uptimekuma.push.di.PushScope
import net.kirkanos.uptimekuma.push.notify.NotificationChannels
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Call [initialize] from `Application.onCreate`: creates the notification channels and, if push is set up,
 * re-registers with the saved distributor (idempotent; a changed endpoint is then pushed to Kuma).
 */
@Singleton
class PushInitializer
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val repository: PushRepositoryImpl,
        @PushScope private val scope: CoroutineScope,
    ) {
        fun initialize() {
            NotificationChannels.ensure(context)
            scope.launch { repository.reRegisterIfConfigured() }
        }
    }
