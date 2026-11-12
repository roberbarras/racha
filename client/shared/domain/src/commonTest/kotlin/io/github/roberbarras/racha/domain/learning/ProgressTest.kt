package io.github.roberbarras.racha.domain.learning

import io.github.roberbarras.racha.domain.library.ContentId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class ProgressTest {
    private val element = ContentId("a".repeat(64))
    private val t0 = Instant.parse("2026-11-12T10:00:00Z")
    private val t1 = Instant.parse("2026-11-12T10:01:00Z")

    @Test
    fun recordsPositionToTheSecondClampedToDuration() {
        val progress = Progress.start(element, 600, t0).recordPosition(754, 600, t1)

        assertEquals(600, progress.positionSec)
        assertEquals(600, progress.maxPositionSec)
        assertEquals(t1, progress.lastWatchedAt)
        assertEquals(t1, progress.updatedAt)
    }

    @Test
    fun maxPositionNeverDecreasesWhenSeekingBack() {
        val progress = Progress.start(element, 600, t0).recordPosition(300, null, t0).recordPosition(120, null, t1)

        assertEquals(120, progress.positionSec)
        assertEquals(300, progress.maxPositionSec)
        assertEquals(20.0, progress.percent)
    }

    @Test
    fun finishingCompletesAtTheEnd() {
        val progress = Progress.start(element, 600, t0).recordPosition(590, null, t0).finish(t1)

        assertTrue(progress.completed)
        assertEquals(600, progress.positionSec)
        assertEquals(100.0, progress.percent)
    }

    @Test
    fun manualMarksKeepTheFurthestPointReached() {
        val watched = Progress.start(element, 600, t0).recordPosition(400, null, t0).markWatched(t1)
        val unwatched = watched.markUnwatched(t1)

        assertTrue(watched.completed)
        assertFalse(unwatched.completed)
        assertEquals(0, unwatched.positionSec)
        assertEquals(400, unwatched.maxPositionSec)
        assertEquals(t1, unwatched.updatedAt)
    }

    @Test
    fun unknownDurationIsZeroPercentUntilCompleted() {
        val progress = Progress.start(element, null, t0).recordPosition(50, null, t1)

        assertEquals(0.0, progress.percent)
        assertEquals(100.0, progress.markWatched(t1).percent)
    }

    @Test
    fun rejectsInconsistentState() {
        assertFailsWith<IllegalArgumentException> { Progress(element, 10, 100, false, 5, null, t0) }
    }
}
