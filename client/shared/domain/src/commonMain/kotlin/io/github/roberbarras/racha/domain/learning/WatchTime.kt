package io.github.roberbarras.racha.domain.learning

import io.github.roberbarras.racha.domain.library.ElementKind
import io.github.roberbarras.racha.domain.library.PlayableElement

/**
 * How long a course or a section is, how much of it has been watched and how much is left. A finished video counts
 * whole; one half watched counts up to the furthest point reached; resources (pages, PDFs…) have no duration.
 */
data class WatchTime(val totalSec: Long, val watchedSec: Long) {
    val leftSec: Long get() = (totalSec - watchedSec).coerceAtLeast(0)

    operator fun plus(other: WatchTime) = WatchTime(totalSec + other.totalSec, watchedSec + other.watchedSec)

    companion object {
        val ZERO = WatchTime(0, 0)

        fun of(elements: List<Pair<PlayableElement, Progress?>>): WatchTime = elements.fold(ZERO) { sum, (element, progress) ->
            if (element.kind != ElementKind.VIDEO || element.archived) return@fold sum
            val duration = element.media.durationSec ?: progress?.durationSec ?: 0
            val watched = when {
                progress == null -> 0
                progress.completed -> duration
                else -> progress.maxPositionSec.coerceAtMost(duration)
            }
            sum + WatchTime(duration, watched)
        }
    }
}
