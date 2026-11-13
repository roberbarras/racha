package io.github.roberbarras.racha.domain.learning

import io.github.roberbarras.racha.domain.library.ContentId
import io.github.roberbarras.racha.domain.library.LibraryItem
import io.github.roberbarras.racha.domain.library.PlayableElement
import io.github.roberbarras.racha.domain.library.scan.CalendarDate
import kotlin.random.Random

/** Why something is suggested in "Recommended today" (spec §2.2 "Recomendado hoy / Aleatorio motivacional"). */
enum class RecommendationReason { NEXT_LESSON, DISCOVER }

data class Recommendation(val item: LibraryItem, val element: PlayableElement, val reason: RecommendationReason)

/**
 * "Recommended today":
 * 1. the next unfinished element of each course/series in progress, most recently active first, skipping what is
 *    already in "Continue watching";
 * 2. then not-started items picked at random with a seed of the day, so the row is stable for the whole day and
 *    changes tomorrow.
 */
object Recommendations {
    /** [elements] must be in course order (section, then element), as the library repository returns them. */
    data class Candidate(val item: LibraryItem, val elements: List<PlayableElement>, val progress: Map<ContentId, Progress>)

    fun today(candidates: List<Candidate>, continueWatching: Set<ContentId>, today: CalendarDate, limit: Int = 4): List<Recommendation> {
        val present = candidates.filterNot { it.item.archived }
        val inProgress = present
            .filter { c -> c.progress.values.any { it.completed || it.positionSec > 0 } }
            .sortedByDescending { c -> c.progress.values.maxOfOrNull { it.updatedAt } }
            .mapNotNull { c ->
                c.elements.filterNot { it.archived }
                    .firstOrNull { e -> c.progress[e.id]?.completed != true && e.id !in continueWatching && c.progress[e.id] == null }
                    ?.let { Recommendation(c.item, it, RecommendationReason.NEXT_LESSON) }
            }
            .filterNot { r -> r.element.id in continueWatching }
        val notStarted = present.filter { c -> c.progress.isEmpty() && c.elements.any { !it.archived } }
            .shuffled(Random(today.toEpochDay()))
            .map { c -> Recommendation(c.item, c.elements.first { !it.archived }, RecommendationReason.DISCOVER) }
        return (inProgress + notStarted).distinctBy { it.item.id }.take(limit)
    }
}
