package io.github.roberbarras.racha.application.browse

import io.github.roberbarras.racha.application.learning.FavoriteRepository
import io.github.roberbarras.racha.application.learning.ProgressRepository
import io.github.roberbarras.racha.application.library.LibraryRepository
import io.github.roberbarras.racha.application.shared.Clock
import io.github.roberbarras.racha.domain.learning.Favorite
import io.github.roberbarras.racha.domain.learning.FavoriteTarget
import io.github.roberbarras.racha.domain.learning.ItemProgress
import io.github.roberbarras.racha.domain.learning.Progress
import io.github.roberbarras.racha.domain.library.ContentId
import io.github.roberbarras.racha.domain.library.PlayableElement

/** Recomputes the denormalised overall progress of a course/series after its elements' progress changed. */
class UpdateItemProgress(private val library: LibraryRepository, private val progress: ProgressRepository) {
    suspend operator fun invoke(item: ContentId) {
        val current = library.item(item) ?: return
        val percent = ItemProgress.percent(library.elements(item), progress.forItem(item).associateBy { it.elementId })
        if (percent != current.progressPct) library.upsertItem(current.copy(progressPct = percent))
    }
}

/**
 * Manual watched/unwatched marks (user request for courses imported half-way): one element, a whole section, the
 * whole course, or "everything up to here".
 */
class MarkProgress(
    private val library: LibraryRepository,
    private val progress: ProgressRepository,
    private val updateItemProgress: UpdateItemProgress,
    private val clock: Clock,
) {
    suspend fun element(id: ContentId, watched: Boolean) {
        val element = library.element(id) ?: return
        apply(listOf(element), watched)
        updateItemProgress(element.libraryItemId)
    }

    suspend fun section(item: ContentId, section: ContentId, watched: Boolean) {
        apply(library.elements(item).filter { it.sectionId == section }, watched)
        updateItemProgress(item)
    }

    suspend fun item(item: ContentId, watched: Boolean) {
        apply(library.elements(item), watched)
        updateItemProgress(item)
    }

    /** Marks every element up to and including [element], in course order, as watched. */
    suspend fun upTo(item: ContentId, element: ContentId) {
        val ordered = library.elements(item)
        val index = ordered.indexOfFirst { it.id == element }
        if (index < 0) return
        apply(ordered.take(index + 1), watched = true)
        updateItemProgress(item)
    }

    private suspend fun apply(elements: List<PlayableElement>, watched: Boolean) {
        val now = clock.now()
        elements.forEach { element ->
            val current = progress.get(element.id) ?: Progress.start(element.id, element.media.durationSec, now)
            if (current.completed != watched || (!watched && current.positionSec > 0)) {
                progress.save(if (watched) current.markWatched(now) else current.markUnwatched(now))
            }
        }
    }
}

/** "Mi lista" (spec §2.2): add or remove a course/series or an element. */
class ToggleFavorite(private val favorites: FavoriteRepository, private val clock: Clock) {
    suspend operator fun invoke(target: FavoriteTarget, favorite: Boolean) {
        if (favorite) favorites.add(Favorite(target, clock.now())) else favorites.remove(target)
    }
}

data class PlayableContext(val element: PlayableElement, val item: io.github.roberbarras.racha.domain.library.LibraryItem, val progress: Progress?)

/** Element, its course and its progress: what a player needs to open something (spec §4.2 resume). */
class GetElement(private val library: LibraryRepository, private val progress: ProgressRepository) {
    suspend operator fun invoke(id: ContentId): PlayableContext? {
        val element = library.element(id) ?: return null
        val item = library.item(element.libraryItemId) ?: return null
        return PlayableContext(element, item, progress.get(id))
    }
}
