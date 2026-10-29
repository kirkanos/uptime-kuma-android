package net.kirkanos.uptimekuma.push.work

import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import net.kirkanos.uptimekuma.core.domain.MonitorStateListener
import net.kirkanos.uptimekuma.core.domain.SessionRepository
import net.kirkanos.uptimekuma.push.R
import net.kirkanos.uptimekuma.push.notify.NotificationChannels

/**
 * One-shot cache refresh after a push alert (`SessionRepository.syncOnce`). After a successful sync every
 * [MonitorStateListener] (multibinding, e.g. the home-screen widget and the Quick Settings tile) is told.
 */
@HiltWorker
class SyncWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val session: SessionRepository,
        private val listeners: Set<@JvmSuppressWildcards MonitorStateListener>,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result =
            try {
                // false = not configured / login required: nothing a retry could fix.
                if (session.syncOnce()) notifyListeners()
                Result.success()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Sync after push failed (attempt $runAttemptCount)", e)
                if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
            }

        private suspend fun notifyListeners() {
            listeners.forEach { listener ->
                try {
                    listener.onMonitorStateChanged()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Monitor state listener failed", e)
                }
            }
        }

        /** Only used if expedited work has to run as a foreground service (not the case on API 31+). */
        override suspend fun getForegroundInfo(): ForegroundInfo {
            NotificationChannels.ensure(applicationContext)
            val notification =
                NotificationCompat
                    .Builder(applicationContext, NotificationChannels.SYNC)
                    .setSmallIcon(R.drawable.ic_stat_kuma)
                    .setContentTitle(applicationContext.getString(R.string.push_sync_notification_title))
                    .setPriority(NotificationCompat.PRIORITY_MIN)
                    .setSilent(true)
                    .build()
            return ForegroundInfo(FOREGROUND_ID, notification)
        }

        companion object {
            const val UNIQUE_NAME: String = "push_sync"
            private const val TAG = "SyncWorker"
            private const val MAX_ATTEMPTS = 3
            private const val FOREGROUND_ID = 0x5379
        }
    }
