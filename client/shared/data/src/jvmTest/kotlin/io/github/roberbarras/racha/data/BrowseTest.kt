package io.github.roberbarras.racha.data

import io.github.roberbarras.racha.application.browse.MarkProgress
import io.github.roberbarras.racha.application.browse.ObserveHome
import io.github.roberbarras.racha.application.browse.ObserveItemDetail
import io.github.roberbarras.racha.application.browse.ObserveMyList
import io.github.roberbarras.racha.application.browse.SearchLibrary
import io.github.roberbarras.racha.application.browse.SetMode
import io.github.roberbarras.racha.application.browse.ToggleFavorite
import io.github.roberbarras.racha.application.browse.UpdateItemProgress
import io.github.roberbarras.racha.application.library.EnsureBuiltInCategories
import io.github.roberbarras.racha.application.library.scan.LocalDates
import io.github.roberbarras.racha.application.preferences.Preferences
import io.github.roberbarras.racha.application.shared.Clock
import io.github.roberbarras.racha.data.Fixtures.root
import io.github.roberbarras.racha.data.Fixtures.section
import io.github.roberbarras.racha.data.Fixtures.video
import io.github.roberbarras.racha.data.Fixtures.watched
import io.github.roberbarras.racha.domain.learning.FavoriteTarget
import io.github.roberbarras.racha.domain.learning.HistoryEntry
import io.github.roberbarras.racha.domain.learning.RecommendationReason
import io.github.roberbarras.racha.domain.library.CategoryKey
import io.github.roberbarras.racha.domain.library.ContentId
import io.github.roberbarras.racha.domain.library.LibraryItem
import io.github.roberbarras.racha.domain.library.Mode
import io.github.roberbarras.racha.domain.library.RelativePath
import io.github.roberbarras.racha.domain.library.scan.CalendarDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class BrowseTest {
    private val now = Instant.parse("2026-11-13T18:00:00Z")
    private val clock = Clock { now }
    private val db = Fixtures.newDatabase()
    private val preferences = Preferences(db.settings, clock)
    private val utc = LocalDates { i -> CalendarDate.ofEpochDay(i.epochSeconds.floorDiv(86_400)) }
    private val home = ObserveHome(db.categories, db.library, db.progress, db.history, preferences, clock, utc)
    private val update = UpdateItemProgress(db.library, db.progress)
    private val mark = MarkProgress(db.library, db.progress, update, clock)

    private val aws = Fixtures.item("AWS Course").copy(firstSeenAt = now - 30.days)
    private val intro = section(aws, "01 - Introduction", 1)
    private val iam = section(aws, "04 - IAM", 4)
    private val lessons = listOf(
        video(aws, intro, "001 Course Introduction.mp4", 1),
        video(aws, intro, "002 Creating an AWS Account.mp4", 2),
        video(aws, iam, "001 IAM Introduction.mp4", 1),
    ).map { it.copy(firstSeenAt = now - 30.days, media = it.media.copy(durationSec = 2710)) } // 45:10
    private val dark: LibraryItem = Fixtures.item("Dark").copy(
        id = ContentId.forFolder(CategoryKey.SERIES, root, RelativePath.of("Dark")), categoryKey = CategoryKey.SERIES, title = "Dark",
    )
    private val darkSeason = section(dark, "Season 1", 1)
    private val episode = video(dark, darkSeason, "S01E01 Secrets.mkv", 1).copy(firstSeenAt = now - 2.hours)

    private suspend fun seed() {
        EnsureBuiltInCategories(db.categories, clock)()
        db.library.upsertItem(aws)
        listOf(intro, iam).forEach { db.library.upsertSection(it) }
        lessons.forEach { db.library.upsertElement(it) }
        db.library.upsertItem(dark)
        db.library.upsertSection(darkSeason)
        db.library.upsertElement(episode)
    }

    @Test
    fun criterion2HomeOpensOnThePrimaryCategoryWithTheLastWatchedElementAndItsTimestamp() = runBlocking {
        seed()
        db.progress.save(watched(lessons[0], 30, now - 3.hours))
        db.progress.save(watched(lessons[1], 754, now - 10.minutes)) // the last one watched, at 12:34

        val view = home().first()

        assertEquals(CategoryKey.COURSES, view.defaultCategory)
        val first = view.continueWatching.first()
        assertEquals(lessons[1].id, first.element.id)
        assertEquals(754L, first.progress.positionSec)
        assertEquals(listOf(lessons[1].id, lessons[0].id), view.continueWatching.map { it.element.id })
    }

    @Test
    fun theConfiguredPrimaryCategoryOpensByDefault() = runBlocking {
        seed()
        db.categories.setPrimary(CategoryKey.LIVE, now)

        assertEquals(CategoryKey.LIVE, home().first().defaultCategory)
    }

    @Test
    fun criterion11ModesFilterCategoriesAndRows() = runBlocking {
        seed()
        db.progress.save(watched(lessons[0], 30, now - 1.hours))
        db.progress.save(watched(episode, 300, now - 5.minutes))

        val study = home().first()
        assertEquals(listOf(CategoryKey.COURSES, CategoryKey.LIVE), study.categories.map { it.category.key })
        assertEquals(listOf(lessons[0].id), study.continueWatching.map { it.element.id })

        SetMode(preferences)(Mode.LEISURE)
        val leisure = home().first()
        assertEquals(listOf(CategoryKey.SERIES, CategoryKey.LIVE), leisure.categories.map { it.category.key })
        assertEquals(listOf(episode.id), leisure.continueWatching.map { it.element.id })
        assertEquals(CategoryKey.SERIES, leisure.defaultCategory, "primary (courses) is hidden in leisure: first visible opens")
        assertEquals(listOf(episode.id), leisure.newlyDetected.map { it.element.id })
    }

    @Test
    fun recommendsTheNextLessonAndCountsTheStreak() = runBlocking {
        seed()
        db.progress.save(watched(lessons[0], 60, now - 1.days).finish(now - 1.days))
        update(aws.id)
        listOf(0, 1, 2).forEach { d -> db.history.add(HistoryEntry("h$d", lessons[0].id, now - d.days, 60)) }

        val view = home().first()

        assertEquals(lessons[1].id to RecommendationReason.NEXT_LESSON, view.recommended.first().let { it.element.id to it.reason })
        assertEquals(3, view.streakDays)
        assertEquals(1, view.categories.first { it.category.key == CategoryKey.COURSES }.itemCount)
    }

    @Test
    fun manualMarksUpdateElementsAndTheCourseProgress() = runBlocking {
        seed()

        mark.upTo(aws.id, lessons[1].id)
        val detail = ObserveItemDetail(db.categories, db.library, db.progress, db.favorites)(aws.id).first()!!

        assertEquals(2, detail.watchedCount)
        assertEquals(lessons[2].id, detail.nextUp?.element?.id)
        assertEquals(2.0 / 3 * 100, db.library.item(aws.id)!!.progressPct, 0.001)
        assertEquals(listOf("01 - Introduction", "04 - IAM"), detail.sections.map { it.section.title })

        mark.section(aws.id, intro.id, watched = false)
        assertEquals(0, ObserveItemDetail(db.categories, db.library, db.progress, db.favorites)(aws.id).first()!!.watchedCount)

        mark.item(aws.id, watched = true)
        assertEquals(100.0, db.library.item(aws.id)!!.progressPct)
        assertEquals(null, ObserveItemDetail(db.categories, db.library, db.progress, db.favorites)(aws.id).first()!!.nextUp)
    }

    @Test
    fun favoritesFeedMyListAndTheDetail() = runBlocking {
        seed()
        val toggle = ToggleFavorite(db.favorites, clock)

        toggle(FavoriteTarget.Item(aws.id), true)
        toggle(FavoriteTarget.Element(episode.id), true)

        val list = ObserveMyList(db.favorites, db.library)().first()
        assertEquals(listOf(aws.id), list.items.map { it.id })
        assertEquals(listOf(episode.id), list.elements.map { it.element.id })
        assertTrue(ObserveItemDetail(db.categories, db.library, db.progress, db.favorites)(aws.id).first()!!.isFavorite)
    }

    @Test
    fun searchMatchesTitlesAndOverridesCaseInsensitively() = runBlocking {
        seed()
        db.library.upsertElement(lessons[2].copy(titleOverride = "Permisos 100% IAM"))
        val search = SearchLibrary(db.library)

        assertEquals(listOf(aws.id), search("aws course").items.map { it.id })
        assertEquals(listOf(lessons[2].id), search("100%").elements.map { it.element.id })
        assertTrue(search("%").isEmpty, "a single character is ignored")
        assertTrue(search("zz_nothing").isEmpty)
    }
}
