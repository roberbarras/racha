package io.github.roberbarras.racha.android.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.roberbarras.racha.android.RachaApplication
import io.github.roberbarras.racha.domain.preferences.Settings
import io.github.roberbarras.racha.files.ReminderTime
import java.util.concurrent.TimeUnit

/**
 * The daily study reminder (§9) at the chosen time. WorkManager runs it around then (the system may batch it a few
 * minutes later to save battery: an exact alarm is not needed for a reminder), and it schedules the next one.
 */
class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as RachaApplication).graph
        graph.studyReminder.remindIfDue()
        schedule(applicationContext, graph.preferences.get(Settings.DailyReminder), graph.preferences.get(Settings.DailyReminderTime))
        return Result.success()
    }

    companion object {
        private const val NAME = "racha-reminder"

        fun schedule(context: Context, enabled: Boolean, time: String) {
            val work = WorkManager.getInstance(context)
            if (!enabled) {
                work.cancelUniqueWork(NAME)
                return
            }
            val request = OneTimeWorkRequestBuilder<ReminderWorker>()
                .setInitialDelay(ReminderTime.untilNext(time).toMillis(), TimeUnit.MILLISECONDS)
                .build()
            work.enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
