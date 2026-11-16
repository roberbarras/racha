package io.github.roberbarras.racha.ui.notifications

import io.github.roberbarras.racha.application.notifications.Notice
import io.github.roberbarras.racha.ui.resources.Res
import io.github.roberbarras.racha.ui.resources.channel_new_content
import io.github.roberbarras.racha.ui.resources.channel_reminder
import io.github.roberbarras.racha.ui.resources.channel_sync
import io.github.roberbarras.racha.ui.resources.notice_new_in
import io.github.roberbarras.racha.ui.resources.notice_new_many
import io.github.roberbarras.racha.ui.resources.notice_new_one
import io.github.roberbarras.racha.ui.resources.notice_reminder_next
import io.github.roberbarras.racha.ui.resources.notice_reminder_pick
import io.github.roberbarras.racha.ui.resources.notice_reminder_streak
import io.github.roberbarras.racha.ui.resources.notice_reminder_title
import io.github.roberbarras.racha.ui.resources.notice_sync_other
import io.github.roberbarras.racha.ui.resources.notice_sync_title
import io.github.roberbarras.racha.ui.resources.notice_sync_unauthorized
import io.github.roberbarras.racha.ui.resources.notice_sync_unreachable
import org.jetbrains.compose.resources.getString

/** A notice in words, plus the channel (Android) it belongs to. */
data class NoticeText(val channel: NoticeChannel, val title: String, val body: String)

enum class NoticeChannel { NEW_CONTENT, SYNC, REMINDER }

/** The app's words for each notice, in the current language; platforms only show them. */
object NoticeTexts {
    suspend fun of(notice: Notice): NoticeText = when (notice) {
        is Notice.NewContent -> NoticeText(
            NoticeChannel.NEW_CONTENT,
            notice.itemTitles.singleOrNull()?.let { getString(Res.string.notice_new_one, it, notice.total) } ?: getString(Res.string.notice_new_many, notice.total),
            if (notice.itemTitles.size == 1) notice.firstTitles.joinToString(" · ")
            else getString(Res.string.notice_new_in, notice.itemTitles.take(3).joinToString(", ")),
        )
        is Notice.SyncFailed -> NoticeText(
            NoticeChannel.SYNC,
            getString(Res.string.notice_sync_title),
            when {
                notice.code == "unreachable" -> getString(Res.string.notice_sync_unreachable)
                notice.code == "unauthorized" || notice.code.startsWith("rejected:401") -> getString(Res.string.notice_sync_unauthorized)
                else -> getString(Res.string.notice_sync_other, notice.code.removePrefix("rejected:"))
            },
        )
        is Notice.StudyReminder -> NoticeText(
            NoticeChannel.REMINDER,
            if (notice.streakDays > 0) getString(Res.string.notice_reminder_streak, notice.streakDays) else getString(Res.string.notice_reminder_title),
            notice.nextTitle?.let { next -> notice.itemTitle?.let { item -> getString(Res.string.notice_reminder_next, next, item) } }
                ?: getString(Res.string.notice_reminder_pick),
        )
    }

    suspend fun channelName(channel: NoticeChannel): String = when (channel) {
        NoticeChannel.NEW_CONTENT -> getString(Res.string.channel_new_content)
        NoticeChannel.SYNC -> getString(Res.string.channel_sync)
        NoticeChannel.REMINDER -> getString(Res.string.channel_reminder)
    }
}
