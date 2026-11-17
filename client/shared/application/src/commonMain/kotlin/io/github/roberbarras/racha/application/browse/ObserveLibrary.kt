package io.github.roberbarras.racha.application.browse

import io.github.roberbarras.racha.application.learning.FavoriteRepository
import io.github.roberbarras.racha.application.learning.HistoryRepository
import io.github.roberbarras.racha.application.learning.ProgressRepository
import io.github.roberbarras.racha.application.library.CategoryRepository
import io.github.roberbarras.racha.application.library.ElementInItem
import io.github.roberbarras.racha.application.library.LibraryRepository
import io.github.roberbarras.racha.application.library.search.ResourceTextIndex
import io.github.roberbarras.racha.application.library.search.TextHit
import io.github.roberbarras.racha.domain.library.SearchText
import io.github.roberbarras.racha.domain.learning.FavoriteTarget
import io.github.roberbarras.racha.domain.learning.HistoryEntry
import io.github.roberbarras.racha.domain.learning.Progress
import io.github.roberbarras.racha.domain.library.Category
import io.github.roberbarras.racha.domain.library.CategoryKey
import io.github.roberbarras.racha.domain.library.ContentId
import io.github.roberbarras.racha.domain.library.LibraryItem
import io.github.roberbarras.racha.domain.library.PlayableElement
import io.github.roberbarras.racha.domain.library.Section
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest

data class CategoryView(val category: Category, val items: List<LibraryItem>)

/** One category's courses/series: present first, archived ones (files gone, progress kept) after (spec §3.4). */
class ObserveCategory(private val categories: CategoryRepository, private val library: LibraryRepository) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(key: CategoryKey): Flow<CategoryView?> =
        combine(categories.observeAll(), library.observeItems(key)) { all, items ->
            all.firstOrNull { it.key == key }?.let { CategoryView(it, items.sortedBy { item -> item.archived }) }
        }
}

data class ElementView(val element: PlayableElement, val progress: Progress?) {
    val watched: Boolean get() = progress?.completed == true
}

data class SectionView(val section: Section, val elements: List<ElementView>)

/** Detail of a course/series/channel (spec §2.2): sections, per-element progress, what to play next. */
data class ItemDetailView(
    val item: LibraryItem,
    val category: Category?,
    val sections: List<SectionView>,
    val isFavorite: Boolean,
    /** The element in progress if any, else the first one not watched; null when everything is watched. */
    val nextUp: ElementView?,
    val watchedCount: Int,
    val totalCount: Int,
)

class ObserveItemDetail(
    private val categories: CategoryRepository,
    private val library: LibraryRepository,
    private val progress: ProgressRepository,
    private val favorites: FavoriteRepository,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(id: ContentId): Flow<ItemDetailView?> =
        combine(library.observeItem(id), library.observeElements(id), progress.observeForItem(id), favorites.observeAll()) { item, elements, progressList, favs ->
            Quad(item, elements, progressList, favs.any { it.target == FavoriteTarget.Item(id) })
        }.mapLatest { (item, elements, progressList, favorite) ->
            if (item == null) return@mapLatest null
            val byElement = progressList.associateBy { it.elementId }
            val views = elements.map { ElementView(it, byElement[it.id]) }
            val sections = library.sections(id).map { section -> SectionView(section, views.filter { it.element.sectionId == section.id }) }
                .filter { it.elements.isNotEmpty() }
            val present = views.filterNot { it.element.archived }
            val inProgress = present.filter { !it.watched && (it.progress?.positionSec ?: 0) > 0 }.maxByOrNull { it.progress?.lastWatchedAt ?: it.progress!!.updatedAt }
            ItemDetailView(
                item = item,
                category = categories.get(item.categoryKey),
                sections = sections,
                isFavorite = favorite,
                nextUp = inProgress ?: present.firstOrNull { !it.watched },
                watchedCount = present.count { it.watched },
                totalCount = present.size,
            )
        }

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
}

data class HistoryRow(val entry: HistoryEntry, val element: PlayableElement, val item: LibraryItem)

class ObserveHistory(private val history: HistoryRepository, private val library: LibraryRepository) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(limit: Int = 200): Flow<List<HistoryRow>> = history.observeRecent(limit).mapLatest { entries ->
        // One lookup for every element shown (not two queries per entry on each change).
        val found = library.elementsInItems(entries.map { it.elementId }.distinct()).associateBy { it.element.id }
        entries.mapNotNull { entry -> found[entry.elementId]?.let { HistoryRow(entry, it.element, it.item) } }
    }
}

data class MyListView(val items: List<LibraryItem>, val elements: List<ElementInItem>)

class ObserveMyList(private val favorites: FavoriteRepository, private val library: LibraryRepository) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(): Flow<MyListView> = favorites.observeAll().mapLatest { favs ->
        MyListView(
            items = favs.mapNotNull { (it.target as? FavoriteTarget.Item)?.let { t -> library.item(t.id) } },
            elements = favs.mapNotNull { fav ->
                (fav.target as? FavoriteTarget.Element)?.let { t ->
                    library.element(t.id)?.let { e -> library.item(e.libraryItemId)?.let { ElementInItem(e, it) } }
                }
            },
        )
    }
}

/** A resource found by what it says, with the words around the match. */
data class ContentMatch(val element: ElementInItem, val snippet: String)

data class SearchResults(
    val query: String,
    val items: List<LibraryItem>,
    val elements: List<ElementInItem>,
    val inContent: List<ContentMatch> = emptyList(),
) {
    val isEmpty: Boolean get() = items.isEmpty() && elements.isEmpty() && inContent.isEmpty()
}

/**
 * Global search (spec §2.2): titles of courses/series and elements, and the text of HTML/TXT/link resources, ignoring
 * case and accents. Queries shorter than 2 characters return nothing.
 */
class SearchLibrary(private val library: LibraryRepository, private val content: ResourceTextIndex? = null) {
    suspend operator fun invoke(query: String): SearchResults {
        val text = query.trim()
        if (text.length < 2) return SearchResults(text, emptyList(), emptyList())
        val elements = library.searchElements(text, 40)
        val byTitle = elements.map { it.element.id }.toSet()
        val folded = SearchText.fold(text)
        val hits = content?.search(folded, 30).orEmpty().filter { it.elementId !in byTitle }
        val found = library.elementsInItems(hits.map { it.elementId }).associateBy { it.element.id }
        val inContent = hits.mapNotNull { hit -> found[hit.elementId]?.let { ContentMatch(it, snippet(hit, folded)) } }
        return SearchResults(text, library.searchItems(text, 20), elements, inContent)
    }

    /** About [SNIPPET] characters of the original text around the match, cut at word boundaries. */
    private fun snippet(hit: TextHit, foldedQuery: String): String {
        val at = SearchText.indexIn(hit.folded, foldedQuery).coerceAtLeast(0)
        var start = (at - SNIPPET / 2).coerceAtLeast(0)
        var end = (at + foldedQuery.length + SNIPPET / 2).coerceAtMost(hit.text.length)
        if (start > 0) start = hit.text.indexOf(' ', start).takeIf { it in start until at }?.plus(1) ?: start
        if (end < hit.text.length) end = hit.text.lastIndexOf(' ', end).takeIf { it > at + foldedQuery.length } ?: end
        val body = hit.text.substring(start, end).replace('\n', ' ')
        return (if (start > 0) "…" else "") + body + (if (end < hit.text.length) "…" else "")
    }

    private companion object {
        const val SNIPPET = 120
    }
}

