package io.github.roberbarras.racha.domain.learning

import io.github.roberbarras.racha.domain.library.ContentId
import io.github.roberbarras.racha.domain.library.PlayableElement

/**
 * Overall progress of a course/series (spec §2.2 "progreso global"): every present element weighs the same;
 * completed counts 1, a partially watched video counts its watched fraction, anything else 0.
 */
object ItemProgress {
    fun percent(elements: List<PlayableElement>, progress: Map<ContentId, Progress>): Double {
        val present = elements.filterNot { it.archived }
        if (present.isEmpty()) return 0.0
        val done = present.sumOf { element -> (progress[element.id]?.percent ?: 0.0) / 100 }
        return (done / present.size * 100).coerceIn(0.0, 100.0)
    }
}
