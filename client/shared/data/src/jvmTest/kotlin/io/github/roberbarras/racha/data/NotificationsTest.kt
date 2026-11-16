package io.github.roberbarras.racha.data

import io.github.roberbarras.racha.application.library.EnsureBuiltInCategories
import io.github.roberbarras.racha.application.library.scan.LocalDates
import io.github.roberbarras.racha.application.notifications.Notice
import io.github.roberbarras.racha.application.notifications.NotifyNewContent
import io.github.roberbarras.racha.application.notifications.NotifySyncFailure
import io.github.roberbarras.racha.application.notifications.StudyReminder
import io.github.roberbarras.racha.application.preferences.Preferences
import io.github.roberbarras.racha.application.shared.Clock
import io.github.roberbarras.racha.data.Fixtures.item
import io.github.roberbarras.racha.data.Fixtures.section
import io.github.roberbarras.racha.data.Fixtures.video
import io.github.roberbarras.racha.domain.learning.HistoryEntry
import io.github.roberbarras.racha.domain.learning.Progress
import io.github.roberbarras.racha.domain.library.CategoryKey
import io.github.roberbarras.racha.domain.library.CategoryRoot
import io.github.roberbarras.racha.domain.library.scan.CalendarDate
import io.github.roberbarras.racha.domain.preferences.Settings
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Notifications (spec §9): new episodes, failed sync and the daily reminder, each only when it helps. */
class NotificationsTest {
    private var now = Instant.parse("2026-11-16T19:00:00Z")
    private val clock = Clock { now }
    private val utc = LocalDates { CalendarDate.ofEpochDay(it.epochSeconds.floorDiv(86_400)) }
    private val db = Fixtures.newDatabase()
    private val preferences = Preferences(db.settings, clock)
    private val shown = mutableListOf<Notice>()
    private val series = item("The Expanse")
    private val season = section(series, "Season 1", 1)
    private val episode1 = video(series, season, "S01E01 Dulcinea.mp4", 1)

    private suspend fun seed() {
        EnsureBuiltInCategories(db.categories, clock)()
        db.categories.upsertRoot(CategoryRoot(Fixtures.root, CategoryKey.COURSES, "file:///series", 0))
        db.library.upsertItem(series)
        db.library.upsertSection(season)
        db.library.upsertElement(episode1)
    }

    @Test
    fun newEpisodesFoundByAnAutomaticScanAreAnnounced() = runBlocking<Unit> {
        seed()
        val notify = NotifyNewContent(db.library, preferences, clock) { shown += it }
        now += 1.days
        val since = notify.mark()
        listOf("S01E02 The Big Empty.mp4", "S01E03 Remember the Cant.mp4").forEachIndexed { i, file ->
            db.library.upsertElement(video(series, season, file, i + 2).copy(firstSeenAt = now + 1.minutes))
        }
        notify.after(since)
        val notice = shown.single() as Notice.NewContent
        assertEquals(2, notice.total)
        assertEquals(listOf("The Expanse"), notice.itemTitles)
        assertEquals(series.id, notice.openItem, "one series: the tap opens it")

        preferences.set(Settings.NotifyNewContent, false)
        db.library.upsertElement(video(series, season, "S01E04 CQB.mp4", 4).copy(firstSeenAt = now + 2.minutes))
        notify.after(since)
        assertEquals(1, shown.size, "turned off")
    }

    @Test
    fun syncFailuresAreAnnouncedOnceAndNotForAShortTimeOffline() = runBlocking<Unit> {
        val notify = NotifySyncFailure(preferences, clock) { shown += it }
        notify.failed("unreachable", lastSuccessAt = now - 3.hours)
        assertTrue(shown.isEmpty(), "an afternoon offline is not a problem")
        notify.failed("unreachable", lastSuccessAt = now - 2.days)
        notify.failed("unreachable", lastSuccessAt = now - 2.days)
        assertEquals(listOf<Notice>(Notice.SyncFailed("unreachable")), shown, "once")
        notify.failed("unauthorized", lastSuccessAt = now - 2.days)
        assertEquals(Notice.SyncFailed("unauthorized"), shown.last())
        notify.succeeded()
        notify.failed("unauthorized", lastSuccessAt = now)
        assertEquals(3, shown.size, "after a success, a new failure is news again")
    }

    @Test
    fun theReminderOnlyComesOnDaysWithoutStudy() = runBlocking<Unit> {
        seed()
        val reminder = StudyReminder(preferences, db.history, db.progress, utc, clock) { shown += it }
        assertEquals(false, reminder.remindIfDue(), "off by default")
        preferences.set(Settings.DailyReminder, true)
        db.history.add(HistoryEntry("y1", episode1.id, now - 1.days, 100))
        db.history.add(HistoryEntry("y2", episode1.id, now - 2.days, 100))
        db.progress.save(Progress.start(episode1.id, 268, now - 1.days).recordPosition(100, 268, now - 1.days))
        assertEquals(true, reminder.remindIfDue())
        val notice = shown.single() as Notice.StudyReminder
        assertEquals(2, notice.streakDays, "the streak still alive until today ends")
        assertEquals("S01E01 Dulcinea", notice.nextTitle)
        assertEquals(episode1.id, notice.openElement)

        db.history.add(HistoryEntry("t1", episode1.id, now - 1.hours, 120))
        assertEquals(false, reminder.remindIfDue(), "already studied today")
    }
}
