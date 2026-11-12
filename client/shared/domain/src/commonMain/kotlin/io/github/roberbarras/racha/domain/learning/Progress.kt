package io.github.roberbarras.racha.domain.learning

import io.github.roberbarras.racha.domain.library.ContentId
import kotlin.time.Instant

/**
 * Viewing progress of one element, to the second (spec §4.3).
 *
 * [maxPositionSec] is the furthest point ever reached: it never decreases, so a sync or a manual "unwatched" can
 * reset [positionSec] without losing that reference (spec §6.3). Sync conflicts on [positionSec]/[completed] are
 * resolved by [updatedAt] (last writer wins).
 */
data class Progress(
    val elementId: ContentId,
    val positionSec: Long,
    val durationSec: Long?,
    val completed: Boolean,
    val maxPositionSec: Long,
    val lastWatchedAt: Instant?,
    val updatedAt: Instant,
) {
    init {
        require(positionSec >= 0) { "positionSec must be >= 0" }
        require(durationSec == null || durationSec >= 0) { "durationSec must be >= 0" }
        require(maxPositionSec >= positionSec) { "maxPositionSec must be >= positionSec" }
    }

    /** 0–100, or 100 when completed; unknown duration counts as 0 unless completed. */
    val percent: Double
        get() = when {
            completed -> 100.0
            durationSec == null || durationSec == 0L -> 0.0
            else -> (positionSec.toDouble() / durationSec * 100).coerceIn(0.0, 100.0)
        }

    /** Periodic or pause/exit save while playing. */
    fun recordPosition(positionSec: Long, durationSec: Long?, at: Instant): Progress {
        val duration = durationSec ?: this.durationSec
        val clamped = positionSec.coerceAtLeast(0).let { if (duration != null) it.coerceAtMost(duration) else it }
        return copy(
            positionSec = clamped,
            durationSec = duration,
            maxPositionSec = maxOf(maxPositionSec, clamped),
            lastWatchedAt = at,
            updatedAt = at,
        )
    }

    /** Playback reached the end (spec §4.3): completed, resume point at the end. */
    fun finish(at: Instant): Progress {
        val end = durationSec ?: positionSec
        return copy(positionSec = end, completed = true, maxPositionSec = maxOf(maxPositionSec, end), lastWatchedAt = at, updatedAt = at)
    }

    /** Manual "mark as watched", e.g. for courses imported half-way through. Does not count as watching. */
    fun markWatched(at: Instant): Progress = copy(completed = true, updatedAt = at)

    /** Manual "mark as unwatched": starts over, keeps [maxPositionSec] as reference. */
    fun markUnwatched(at: Instant): Progress = copy(completed = false, positionSec = 0, updatedAt = at)

    companion object {
        fun start(elementId: ContentId, durationSec: Long?, at: Instant) =
            Progress(elementId, 0, durationSec, completed = false, maxPositionSec = 0, lastWatchedAt = null, updatedAt = at)
    }
}
