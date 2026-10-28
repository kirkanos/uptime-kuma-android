package net.kirkanos.uptimekuma.push.work

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import net.kirkanos.uptimekuma.push.data.PushRepositoryImpl

/** Writes a changed UnifiedPush endpoint into the existing Kuma webhook notification. */
@HiltWorker
class EndpointUpdateWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val repository: PushRepositoryImpl,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result =
            try {
                repository.updateKumaEndpoint()
                Result.success()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Updating the Kuma notification failed (attempt $runAttemptCount)", e)
                if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
            }

        companion object {
            const val UNIQUE_NAME: String = "push_endpoint_update"
            private const val TAG = "EndpointUpdateWorker"
            private const val MAX_ATTEMPTS = 8
        }
    }
