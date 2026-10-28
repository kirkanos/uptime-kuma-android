package net.kirkanos.uptimekuma.push.work

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** Background work triggered by push events. */
interface PushWorkScheduler {
    /** Refresh the local cache from Kuma right after an alert arrived. */
    fun enqueueSync()

    /** Push the changed UnifiedPush endpoint into the Kuma webhook notification (retried until online). */
    fun enqueueEndpointUpdate()
}

internal class WorkManagerPushScheduler
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : PushWorkScheduler {
        private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        override fun enqueueSync() {
            val request =
                OneTimeWorkRequestBuilder<SyncWorker>()
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .setConstraints(online)
                    .build()
            // REPLACE: a sync that started before this alert may already have read stale data.
            workManager()?.enqueueUniqueWork(SyncWorker.UNIQUE_NAME, ExistingWorkPolicy.REPLACE, request)
        }

        override fun enqueueEndpointUpdate() {
            val request =
                OneTimeWorkRequestBuilder<EndpointUpdateWorker>()
                    .setConstraints(online)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                    .build()
            workManager()?.enqueueUniqueWork(EndpointUpdateWorker.UNIQUE_NAME, ExistingWorkPolicy.REPLACE, request)
        }

        /** `null` (logged) if the app did not wire WorkManager's on-demand initialization; never crash a push. */
        private fun workManager(): WorkManager? =
            try {
                WorkManager.getInstance(context)
            } catch (e: IllegalStateException) {
                Log.e(TAG, "WorkManager not initialized; is Configuration.Provider implemented by the app?", e)
                null
            }

        private companion object {
            const val TAG = "PushWorkScheduler"
            const val BACKOFF_SECONDS = 30L
        }
    }
