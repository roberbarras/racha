package io.github.roberbarras.racha.data

import io.github.roberbarras.racha.application.library.EnsureBuiltInCategories
import io.github.roberbarras.racha.application.library.scan.LocalDates
import io.github.roberbarras.racha.application.playback.PlaybackController
import io.github.roberbarras.racha.application.shared.Clock
import io.github.roberbarras.racha.application.stats.LoadPersonalStats
import io.github.roberbarras.racha.application.stats.RemoteStats
import io.github.roberbarras.racha.application.stats.DeviceTime
import io.github.roberbarras.racha.application.stats.SessionStats
import io.github.roberbarras.racha.application.stats.RemoteDetails
import io.github.roberbarras.racha.data.Fixtures.item
import io.github.roberbarras.racha.data.Fixtures.section
import io.github.roberbarras.racha.data.Fixtures.video
import io.github.roberbarras.racha.domain.learning.HistoryEntry
import io.github.roberbarras.racha.domain.learning.LocalStat
import io.github.roberbarras.racha.domain.learning.Progress
import io.github.roberbarras.racha.domain.library.CategoryKey
import io.github.roberbarras.racha.domain.library.CategoryRoot
import io.github.roberbarras.racha.domain.library.scan.CalendarDate
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** Personal statistics (spec §2.2): offline from local data, merged with every device's figures when signed in. */
class PersonalStatsTest {
    private val now = Instant.parse("2026-11-16T20:00:00Z")
    private val clock = Clock { now }
    private val utc = LocalDates { CalendarDate.ofEpochDay(it.epochSeconds.floorDiv(86_400)) }
    private val db = Fixtures.newDatabase()
    private val today = CalendarDate(2026, 11, 16)

    private fun load(remote: RemoteStats? = null) =
        LoadPersonalStats(db.categories, db.library, db.progress, db.history, db.stats, utc, clock) { remote }

    private suspend fun seed() {
        EnsureBuiltInCategories(db.categories, clock)()
        db.categories.upsertRoot(CategoryRoot(Fixtures.root, CategoryKey.COURSES, "file:///cursos", 0))
        val course = item().copy(progressPct = 100.0, totalItems = 1)
        val intro = section(course, "01", 1)
        val lesson = video(course, intro, "001 Intro.mp4", 1)
        db.library.upsertItem(course)
        db.library.upsertSection(intro)
        db.library.upsertElement(lesson)
        db.progress.save(Progress.start(lesson.id, 600, now).recordPosition(600, 600, now).finish(now))
        // Watched today, yesterday and the day before; and a 5-day run in October.
        (0..2).forEach { db.history.add(HistoryEntry("h$it", lesson.id, now - it.days, 600)) }
        (0..4).forEach { db.history.add(HistoryEntry("o$it", lesson.id, Instant.parse("2026-10-01T10:00:00Z") + it.days, 600)) }
        db.stats.save(LocalStat(PlaybackController.WATCH_SECONDS, "2026-11-16", 1_800.0, now))
        db.stats.save(LocalStat(PlaybackController.WATCH_SECONDS, "2026-11-10", 600.0, now))
        db.stats.save(LocalStat(PlaybackController.categoryMetric("courses"), "2026-11-16", 2_400.0, now))
    }

    @Test
    fun thisDeviceOnly() = runBlocking {
        seed()
        val s = load()()
        assertFalse(s.allDevices)
        assertEquals(today, s.today)
        assertEquals(2_400, s.totalSeconds)
        assertEquals(2_400, s.last7DaysSeconds)
        assertEquals(3, s.streakDays)
        assertEquals(5, s.bestStreakDays)
        assertEquals(8, s.activeDays, "days in the history, like the streak on Home")
        assertEquals(1, s.completedElements)
        assertEquals(listOf("AWS Course"), s.completedItems.map { it.displayTitle })
        assertEquals(LoadPersonalStats.DAYS, s.days.size)
        assertEquals(today, s.days.last().date)
        assertEquals(1_800, s.days.last().seconds)
        assertEquals(listOf(CategoryKey.COURSES to 2_400L), s.byCategory.map { it.category.key to it.seconds })
    }

    @Test
    fun theServersFiguresForEveryDeviceAreMergedIn() = runBlocking {
        seed()
        val remote = RemoteStats(
            watchSeconds = 10_000,
            byDay = mapOf(today to 1_000L, CalendarDate(2026, 11, 15) to 7_200L),
            byCategory = mapOf("courses" to 9_000L, "series" to 1_000L),
        )
        val s = load(remote)()
        assertTrue(s.allDevices)
        assertEquals(10_000, s.totalSeconds)
        assertEquals(1_800 + 7_200 + 600, s.last7DaysSeconds, "per day, the larger of this device and the server")
        assertEquals(listOf(CategoryKey.COURSES to 9_000L, CategoryKey.SERIES to 1_000L), s.byCategory.map { it.category.key to it.seconds })
        assertEquals(7_200, s.days.single { it.date == CalendarDate(2026, 11, 15) }.seconds)
    }

    @Test
    fun withoutRemoteTheServerIsNotAsked() = runBlocking {
        seed()
        var asked = false
        val s = LoadPersonalStats(db.categories, db.library, db.progress, db.history, db.stats, utc, clock) { asked = true; null }(includeRemote = false)
        assertFalse(asked)
        assertFalse(s.allDevices)
    }
    // H16: when, how and what was watched, from this device's running totals and the server's breakdowns.

    private suspend fun seedBreakdowns() {
        val all = PlaybackController.ALL_TIME
        db.stats.save(LocalStat(PlaybackController.hourMetric(21), all, 3_600.0, now))
        db.stats.save(LocalStat(PlaybackController.hourMetric(8), all, 600.0, now))
        db.stats.save(LocalStat(PlaybackController.weekdayMetric(1), all, 4_200.0, now))
        db.stats.save(LocalStat(PlaybackController.SESSIONS, all, 4.0, now))
        db.stats.save(LocalStat(PlaybackController.SESSION_SECONDS, all, 4_200.0, now))
        db.stats.save(LocalStat(PlaybackController.SESSION_SPEED_SECONDS, all, 6_300.0, now))
        db.stats.save(LocalStat(PlaybackController.SESSIONS_WITH_SUBTITLES, all, 1.0, now))
        db.stats.save(LocalStat(PlaybackController.itemMetric(item().id.value), all, 4_200.0, now))
    }

    @Test
    fun breakdownsOfThisDevice() = runBlocking {
        seed()
        seedBreakdowns()
        val s = load()()
        assertEquals(3_600L, s.byHour[21])
        assertEquals(600L, s.byHour[8])
        assertEquals(24, s.byHour.size)
        assertEquals(listOf(4_200L, 0, 0, 0, 0, 0, 0), s.byWeekday, "Monday first")
        assertEquals(SessionStats(4, 1_050, 1.5, 0.25), s.sessions)
        val title = s.titles.single()
        assertEquals("AWS Course" to 4_200L, title.item.displayTitle to title.seconds)
        assertEquals(1 to 1, title.completed to title.total)
        assertEquals(8, s.recent.size, "the history, newest first")
        assertEquals("AWS Course", s.recent.first().item?.displayTitle)
        assertTrue(s.recent.first().completed)
        assertTrue(s.devices.isEmpty(), "other devices are only known through the server")
    }

    @Test
    fun theServersBreakdownsAddTheOtherDevices() = runBlocking {
        seed()
        seedBreakdowns()
        val details = RemoteDetails(
            sessions = SessionStats(40, 900, 1.25, 0.5),
            byHour = mapOf(21 to 1_000L, 22 to 7_200L),
            byWeekday = mapOf(7 to 9_000L),
            devices = listOf(DeviceTime("Pixel 8", "android", 30_000), DeviceTime("Portátil", "linux", 6_000)),
            titleSeconds = mapOf(item().id.value to 36_000L),
        )
        val s = load(RemoteStats(10_000, emptyMap(), emptyMap(), details))()
        assertEquals(3_600L, s.byHour[21], "the larger of this device and the server")
        assertEquals(7_200L, s.byHour[22])
        assertEquals(9_000L, s.byWeekday[6])
        assertEquals(40, s.sessions.count, "the server's sessions include this device's")
        assertEquals(36_000L, s.titles.single().seconds)
        assertEquals(listOf("Pixel 8", "Portátil"), s.devices.map { it.name })
    }
}
