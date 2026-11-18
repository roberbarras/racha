package io.github.roberbarras.racha.android.remote

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.roberbarras.racha.android.R
import io.github.roberbarras.racha.android.RachaApplication
import io.github.roberbarras.racha.android.notifications.AndroidNotifier
import io.github.roberbarras.racha.android.notifications.AndroidNotifier.Companion.id
import io.github.roberbarras.racha.application.remote.DownloadState
import io.github.roberbarras.racha.ui.notifications.NoticeChannel
import io.github.roberbarras.racha.ui.notifications.NoticeTexts
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Remote folder downloads (H19) outside the app: WorkManager runs them with network (Wi-Fi when the folder asks for
 * it, battery not low), as a foreground task with its progress in a notification, so leaving the app does not stop
 * a long download; what is not finished resumes next time.
 */
class RemoteDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val graph get() = (applicationContext as RachaApplication).graph

    override suspend fun doWork(): Result = coroutineScope {
        val remote = graph.remoteSources
        val id = inputData.getString(ID)
        runCatching { setForeground(foreground(null, 0, 0)) }
        val progress = launch {
            remote.downloads.map { states -> states.entries.firstNotNullOfOrNull { (key, s) -> (s as? DownloadState.Downloading)?.let { key to it } } }
                .distinctUntilChanged { a, b -> a?.second?.doneFiles == b?.second?.doneFiles && a?.first == b?.first }
                .collect { current ->
                    val name = current?.let { (key, _) -> remote.all().firstOrNull { it.id == key }?.name }
                    runCatching { setForeground(foreground(name, (current?.second?.doneFiles ?: 0) + 1, current?.second?.files ?: 0)) }
                }
        }
        val metered = applicationContext.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: true
        if (id != null) remote.download(id) else remote.downloadAll(onUnmetered = !metered)
        progress.cancel()
        Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foreground(null, 0, 0)

    private suspend fun foreground(folder: String?, done: Int, total: Int): ForegroundInfo {
        AndroidNotifier(applicationContext).ensureChannel(NoticeChannel.DOWNLOADS)
        val text = NoticeTexts.downloading(folder, done, total)
        val notification = NotificationCompat.Builder(applicationContext, NoticeChannel.DOWNLOADS.id)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(text.title)
            .setContentText(text.body)
            .setOngoing(true)
            .setSilent(true)
            .apply { if (total > 0) setProgress(total, done - 1, false) else setProgress(0, 0, true) }
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ForegroundInfo(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(NOTIFICATION, notification)
    }

    companion object {
        private const val ID = "source"
        private const val PERIODIC = "racha-remote-periodic"
        private const val NOTIFICATION = 4_219

        private fun constraints(wifiOnly: Boolean) = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        /** Now (or as soon as the network allows): one folder, or all of them with null. */
        fun now(context: Context, id: String?, wifiOnly: Boolean) {
            val request = OneTimeWorkRequestBuilder<RemoteDownloadWorker>()
                .setConstraints(constraints(wifiOnly))
                .setInputData(workDataOf(ID to id))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("racha-remote-${id ?: "all"}", ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
        }

        /** New lessons in the chosen courses, every 6 hours (with the same conditions). */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RemoteDownloadWorker>(6, TimeUnit.HOURS).setConstraints(constraints(false)).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
