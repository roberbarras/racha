package io.github.roberbarras.racha.domain.playback

import io.github.roberbarras.racha.domain.learning.Progress
import io.github.roberbarras.racha.domain.library.ContentId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class PlaybackRulesTest {
    private val id = ContentId("a".repeat(64))
    private val t = Instant.parse("2026-11-13T10:00:00Z")
    private fun at(position: Long, duration: Long = 2710) = Progress.start(id, duration, t).recordPosition(position, null, t)

    @Test
    fun resumesOnlyMeaningfulPositions() {
        assertEquals(754L, PlaybackRules.resumePosition(at(754)))
        assertNull(PlaybackRules.resumePosition(at(5)), "too early")
        assertNull(PlaybackRules.resumePosition(at(2705)), "practically finished")
        assertNull(PlaybackRules.resumePosition(at(754).finish(t)), "completed")
        assertNull(PlaybackRules.resumePosition(null))
    }

    @Test
    fun startPositionHonoursModeAndSkippedIntro() {
        assertEquals(754L, PlaybackRules.startPosition(StartMode.RESUME, at(754), introSec = 30))
        assertEquals(30L, PlaybackRules.startPosition(StartMode.BEGINNING, at(754), introSec = 30))
        assertEquals(30L, PlaybackRules.startPosition(StartMode.RESUME, at(20), introSec = 30), "never inside the intro")
        assertEquals(0L, PlaybackRules.startPosition(StartMode.RESUME, null, introSec = 0))
    }

    @Test
    fun outroAndLoop() {
        assertEquals(2650L, PlaybackRules.outroStart(2710, 60))
        assertNull(PlaybackRules.outroStart(2710, 0))
        assertNull(PlaybackRules.outroStart(null, 60))
        assertEquals(1_000L, PlaybackRules.loopTarget(5_000, 1_000, 5_000))
        assertNull(PlaybackRules.loopTarget(4_999, 1_000, 5_000))
        assertNull(PlaybackRules.loopTarget(9_000, 1_000, null))
    }
}
