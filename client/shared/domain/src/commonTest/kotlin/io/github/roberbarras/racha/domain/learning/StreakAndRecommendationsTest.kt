package io.github.roberbarras.racha.domain.learning

import io.github.roberbarras.racha.domain.library.CategoryKey
import io.github.roberbarras.racha.domain.library.ContentId
import io.github.roberbarras.racha.domain.library.ElementKind
import io.github.roberbarras.racha.domain.library.LibraryItem
import io.github.roberbarras.racha.domain.library.MediaInfo
import io.github.roberbarras.racha.domain.library.PlayableElement
import io.github.roberbarras.racha.domain.library.RelativePath
import io.github.roberbarras.racha.domain.library.RootKey
import io.github.roberbarras.racha.domain.library.StructureKind
import io.github.roberbarras.racha.domain.library.scan.CalendarDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class StreakAndRecommendationsTest {
    private val today = CalendarDate(2026, 11, 13)
    private val root = RootKey("0b4f1b5e-6d1f-4a35-9a51-6a3a1b0c9d10")
    private val t0 = Instant.parse("2026-11-13T08:00:00Z")

    @Test
    fun calendarDatesConvertToEpochDaysBothWays() {
        assertEquals(0L, CalendarDate(1970, 1, 1).toEpochDay())
        assertEquals(CalendarDate(2024, 2, 29), CalendarDate(2024, 3, 1).plusDays(-1))
        assertEquals(CalendarDate(2027, 1, 1), CalendarDate(2026, 12, 31).plusDays(1))
        (0L..800L).forEach { d -> assertEquals(today.toEpochDay() + d, today.plusDays(d).toEpochDay()) }
    }

    @Test
    fun streakCountsConsecutiveDaysAndSurvivesUntilTheDayEnds() {
        val days = (1L..6L).map { today.plusDays(-it) }.toSet()
        assertEquals(6, Streak.current(days, today), "nothing watched yet today: still a 6-day streak")
        assertEquals(7, Streak.current(days + today, today))
        assertEquals(0, Streak.current(setOf(today.plusDays(-2)), today), "a missed day breaks it")
    }

    private fun item(name: String) = LibraryItem(
        ContentId.forFolder(CategoryKey.COURSES, root, RelativePath.of(name)), CategoryKey.COURSES, root, RelativePath.of(name),
        StructureKind.COURSE, name, null, null, null, 0, null, 3, 0, 0.0, false, t0, t0,
    )

    private fun elements(item: LibraryItem) = (1..3).map { n ->
        val path = item.relativePath.resolve("00$n lesson.mp4")
        PlayableElement(
            ContentId.forVideo(CategoryKey.COURSES, root, path), item.id, item.id, ElementKind.VIDEO, null, "Lesson $n", null, path,
            MediaInfo.UNKNOWN, n, null, false, 1, t0, null, t0, t0,
        )
    }

    @Test
    fun nextLessonsComeFirstThenADailyStableDiscovery() {
        val aws = item("AWS")
        val awsElements = elements(aws)
        val k8s = item("K8s")
        val go = item("Go")
        val pg = item("PostgreSQL")
        val candidates = listOf(
            Recommendations.Candidate(aws, awsElements, mapOf(awsElements[0].id to Progress.start(awsElements[0].id, 60, t0).finish(t0))),
            Recommendations.Candidate(k8s, elements(k8s), emptyMap()),
            Recommendations.Candidate(go, elements(go), emptyMap()),
            Recommendations.Candidate(pg, elements(pg), emptyMap()),
        )

        val picks = Recommendations.today(candidates, continueWatching = emptySet(), today = today, limit = 3)

        assertEquals(awsElements[1].id to RecommendationReason.NEXT_LESSON, picks.first().let { it.element.id to it.reason })
        assertEquals(3, picks.size)
        assertEquals(picks, Recommendations.today(candidates, emptySet(), today, 3), "stable during the day")
        assertEquals(listOf("Lesson 1"), picks.drop(1).map { it.element.title }.distinct(), "discoveries start at the first lesson")
    }

    @Test
    fun skipsWhatIsAlreadyInContinueWatching() {
        val aws = item("AWS")
        val e = elements(aws)
        val progress = mapOf(e[0].id to Progress.start(e[0].id, 60, t0).recordPosition(30, null, t0 + 1.minutes))

        val picks = Recommendations.today(listOf(Recommendations.Candidate(aws, e, progress)), continueWatching = setOf(e[0].id), today = today)

        assertEquals(listOf(e[1].id), picks.map { it.element.id })
    }
}
