package app.suply.echoair.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.suply.echoair.data.api.EchoScanRequest
import app.suply.echoair.data.api.SuplyApi
import app.suply.echoair.data.db.PendingUploadDao
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Drains the pending_uploads queue. Scheduled whenever an online submission
 * fails, or whenever network connectivity returns.
 */
@HiltWorker
class UploadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val api: SuplyApi,
    private val dao: PendingUploadDao,
    private val json: Json
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val pending = dao.all()
        if (pending.isEmpty()) return Result.success()

        var failed = false
        pending.forEach { row ->
            try {
                val req = json.decodeFromString(EchoScanRequest.serializer(), row.payloadJson)
                api.echoScan(req)
                dao.delete(row.id)
            } catch (t: Throwable) {
                Timber.w(t, "upload retry failed for row ${row.id}")
                dao.update(row.copy(attempts = row.attempts + 1, lastAttemptAt = System.currentTimeMillis()))
                failed = true
            }
        }
        return if (failed) Result.retry() else Result.success()
    }

    companion object {
        private const val UNIQUE_NAME = "echoair-upload"

        fun enqueue(wm: WorkManager) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            wm.enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
