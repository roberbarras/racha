package io.github.roberbarras.racha.domain.playback

import io.github.roberbarras.racha.domain.learning.Progress
import io.github.roberbarras.racha.domain.library.ContentId
import io.github.roberbarras.racha.domain.library.PlayableElement

/** How playback should start (spec §4.2 "Continuar viendo" overlay: resume / start over). */
enum class StartMode {
    /** Offer "Resume from mm:ss" / "Start over" when there is something worth resuming. */
    ASK,
    RESUME,
    BEGINNING,
}

/** Pure playback rules shared by every player. Positions in seconds unless stated. */
object PlaybackRules {
    /** Below this, or this close to the end, resuming is not worth asking. */
    const val RESUME_THRESHOLD_SEC = 10L

    /** Where an unfinished element left off, if it is worth resuming. */
    fun resumePosition(progress: Progress?): Long? {
        if (progress == null || progress.completed) return null
        val position = progress.positionSec
        val duration = progress.durationSec
        if (position < RESUME_THRESHOLD_SEC) return null
        if (duration != null && position > duration - RESUME_THRESHOLD_SEC) return null
        return position
    }

    /** First position to load: the resume point (unless starting over), never inside the skipped intro. */
    fun startPosition(mode: StartMode, progress: Progress?, introSec: Int): Long {
        val resume = if (mode == StartMode.BEGINNING) null else resumePosition(progress)
        return maxOf(resume ?: 0L, introSec.toLong())
    }

    /** Position where the outro starts, treated as the end of the element; null when not skipping. */
    fun outroStart(durationSec: Long?, outroSec: Int): Long? =
        if (outroSec > 0 && durationSec != null && durationSec > outroSec) durationSec - outroSec else null

    /** Next present element in course order (videos and resources alike), or null at the end. */
    fun next(queue: List<PlayableElement>, current: ContentId): PlayableElement? =
        queue.dropWhile { it.id != current }.drop(1).firstOrNull { !it.archived }

    fun previous(queue: List<PlayableElement>, current: ContentId): PlayableElement? =
        queue.takeWhile { it.id != current }.lastOrNull { !it.archived }

    /** A-B loop (spec §4.2): when playback passes B, jump back to A. Milliseconds. */
    fun loopTarget(positionMs: Long, aMs: Long?, bMs: Long?): Long? =
        if (aMs != null && bMs != null && bMs > aMs && positionMs >= bMs) aMs else null
}
