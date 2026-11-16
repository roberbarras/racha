package io.github.roberbarras.racha.application.notifications

import io.github.roberbarras.racha.application.learning.HistoryRepository
import io.github.roberbarras.racha.application.learning.ProgressRepository
import io.github.roberbarras.racha.application.library.LibraryRepository
import io.github.roberbarras.racha.application.library.scan.LocalDates
import io.github.roberbarras.racha.application.preferences.Preferences
import io.github.roberbarras.racha.application.shared.Clock
import io.github.roberbarras.racha.domain.learning.Streak
import io.github.roberbarras.racha.domain.library.ContentId
import io.github.roberbarras.racha.domain.preferences.Settings
import kotlinx.coroutines.flow.first
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** What the app tells the user outside its window (spec §9 "Notificaciones"). Platforms word and show it. */
sealed interface Notice {
    /** New lessons/episodes in [itemTitles] (most recent first); [openItem] when they are all in one course/series. */
    data class NewContent(val total: Int, val itemTitles: List<String>, val firstTitles: List<String>, val openItem: ContentId?) : Notice

    /** Sync stopped working ([code] as in [io.github.roberbarras.racha.application.sync.reportCode]). */
    data class SyncFailed(val code: String) : Notice

    /** "Today's turn": what to continue, and the streak at stake. */
    data class StudyReminder(val streakDays: Int, val nextTitle: String?, val itemTitle: String?, val openElement: ContentId?) : Notice
}

/** Outbound port: shows a notice with the platform's notifications. */
fun interface Notifier {
    suspend fun show(notice: Notice)
}

/**
 * "New episodes detected" (§9): after automatic scans (start-up, folder watcher), not after adding a folder, whose whole
 * content is new by definition, nor after a manual scan the user is watching.
 */
class NotifyNewContent(
    private val library: LibraryRepository,
    private val preferences: Preferences,
    private val clock: Clock,
    private val notifier: Notifier,
) {
    /** Call before the scan; pass the result to [after]. */
    fun mark(): Instant = clock.now()

    suspend fun after(since: Instant) {
        if (!preferences.get(Settings.NotifyNewContent)) return
        val fresh = library.observeNewElements(since, LIMIT).first()
        if (fresh.isEmpty()) return
        val items = fresh.map { it.item }.distinctBy { it.id }
        notifier.show(
            Notice.NewContent(
                total = fresh.size,
                itemTitles = items.map { it.displayTitle },
                firstTitles = fresh.take(3).map { it.element.displayTitle },
                openItem = items.singleOrNull()?.id,
            ),
        )
    }

    private companion object {
        const val LIMIT = 200
    }
}

/**
 * "Sync failed" (§9), without noise: once per kind of failure; an unreachable server only after a day without a
 * successful sync (a laptop offline for an afternoon is not a problem).
 */
class NotifySyncFailure(private val preferences: Preferences, private val clock: Clock, private val notifier: Notifier) {
    private var notified: String? = null

    suspend fun failed(code: String, lastSuccessAt: Instant?) {
        if (!preferences.get(Settings.NotifySyncFailed) || code == notified) return
        if (code == "unreachable" && lastSuccessAt != null && clock.now() - lastSuccessAt < 1.days) return
        notified = code
        notifier.show(Notice.SyncFailed(code))
    }

    fun succeeded() {
        notified = null
    }
}

/** The daily study reminder (§9): only if nothing was watched or read today. Platforms decide when to call it. */
class StudyReminder(
    private val preferences: Preferences,
    private val history: HistoryRepository,
    private val progress: ProgressRepository,
    private val localDates: LocalDates,
    private val clock: Clock,
    private val notifier: Notifier,
) {
    /** True if a reminder was shown. */
    suspend fun remindIfDue(): Boolean {
        if (!preferences.get(Settings.DailyReminder)) return false
        val now = clock.now()
        val today = localDates.dateOf(now)
        val days = history.watchTimesSince(now - STREAK_WINDOW).map(localDates::dateOf).toSet()
        if (today in days) return false
        val next = progress.observeContinueWatching(1).first().firstOrNull()
        notifier.show(Notice.StudyReminder(Streak.current(days, today), next?.element?.displayTitle, next?.item?.displayTitle, next?.element?.id))
        return true
    }

    private companion object {
        val STREAK_WINDOW = (366 * 24).hours
    }
}
