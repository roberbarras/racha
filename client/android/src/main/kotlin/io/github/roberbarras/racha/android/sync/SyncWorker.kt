package io.github.roberbarras.racha.android.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.roberbarras.racha.android.RachaApplication
import io.github.roberbarras.racha.application.sync.RemoteError
import io.github.roberbarras.racha.application.sync.SyncOutcome
import java.util.concurrent.TimeUnit

/**
 * Sync while the app is not visible (spec §7.4 "en background cada X minutos y al recuperar red"). The system runs
 * it only with network and battery not low, batched with other apps' work; Android's minimum period is 15 minutes.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as RachaApplication).graph
        return when (val outcome = graph.syncEngine.syncNow()) {
            is SyncOutcome.Failed -> if (outcome.error is RemoteError.Unreachable && runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
            else -> Result.success()
        }
    }

    companion object {
        private const val PERIODIC = "racha-sync"
        private const val ONCE = "racha-sync-pending"
        private const val MAX_RETRIES = 3
        private const val MIN_PERIOD_MINUTES = 15L

        private val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        /** Keeps the periodic sync in line with the settings: on with the account, every max(N, 15) minutes. */
        fun schedule(context: Context, enabled: Boolean, intervalMinutes: Int) {
            val work = WorkManager.getInstance(context)
            if (!enabled) {
                work.cancelUniqueWork(PERIODIC)
                work.cancelUniqueWork(ONCE)
                return
            }
            val request = PeriodicWorkRequestBuilder<SyncWorker>(maxOf(intervalMinutes.toLong(), MIN_PERIOD_MINUTES), TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            work.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        /** Leaving the app with changes not sent yet: send them as soon as there is network. */
        fun flushSoon(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(constraints).build()
            WorkManager.getInstance(context).enqueueUniqueWork(ONCE, ExistingWorkPolicy.KEEP, request)
        }
    }
}
