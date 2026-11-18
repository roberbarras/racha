package io.github.roberbarras.racha.android.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import io.github.roberbarras.racha.android.MainActivity
import io.github.roberbarras.racha.android.R
import io.github.roberbarras.racha.application.notifications.Notice
import io.github.roberbarras.racha.application.notifications.Notifier
import io.github.roberbarras.racha.ui.app.DeepLink
import io.github.roberbarras.racha.ui.notifications.NoticeChannel
import io.github.roberbarras.racha.ui.notifications.NoticeTexts

/** Notifications on Android: one channel per kind (the user can silence each in the system), tap opens the app there. */
class AndroidNotifier(private val context: Context) : Notifier {
    private val manager = NotificationManagerCompat.from(context)

    override suspend fun show(notice: Notice) {
        if (!allowed(context)) return
        val text = NoticeTexts.of(notice)
        ensureChannel(text.channel)
        val link = when (notice) {
            is Notice.NewContent -> notice.openItem?.let { DeepLink.item(it) }
            is Notice.StudyReminder -> notice.openElement?.let { DeepLink.play(it) }
            is Notice.SyncFailed -> DeepLink.ACCOUNT
        }
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            link?.let { data = it.toUri() }
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val tap = PendingIntent.getActivity(context, text.channel.ordinal, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, text.channel.id)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(text.title)
            .setContentText(text.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.body))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        @Suppress("MissingPermission") // checked by allowed()
        manager.notify(text.channel.ordinal, notification) // one per kind: a newer notice replaces the previous one
    }

    suspend fun ensureChannel(channel: NoticeChannel) {
        val system = context.getSystemService(NotificationManager::class.java)
        if (system.getNotificationChannel(channel.id) != null) return
        val importance = if (channel == NoticeChannel.SYNC || channel == NoticeChannel.DOWNLOADS) NotificationManager.IMPORTANCE_LOW else NotificationManager.IMPORTANCE_DEFAULT
        system.createNotificationChannel(NotificationChannel(channel.id, NoticeTexts.channelName(channel), importance))
    }

    companion object {
        val NoticeChannel.id: String get() = "racha-" + name.lowercase()

        fun allowed(context: Context): Boolean =
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
                NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
}
