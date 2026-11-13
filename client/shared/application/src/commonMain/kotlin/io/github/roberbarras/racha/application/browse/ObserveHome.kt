package io.github.roberbarras.racha.application.browse

import io.github.roberbarras.racha.application.learning.ContinueWatchingEntry
import io.github.roberbarras.racha.application.learning.HistoryRepository
import io.github.roberbarras.racha.application.learning.ProgressRepository
import io.github.roberbarras.racha.application.library.CategoryRepository
import io.github.roberbarras.racha.application.library.ElementInItem
import io.github.roberbarras.racha.application.library.LibraryRepository
import io.github.roberbarras.racha.application.library.scan.LocalDates
import io.github.roberbarras.racha.application.preferences.Preferences
import io.github.roberbarras.racha.application.shared.Clock
import io.github.roberbarras.racha.domain.learning.Recommendation
import io.github.roberbarras.racha.domain.learning.Recommendations
import io.github.roberbarras.racha.domain.learning.Streak
import io.github.roberbarras.racha.domain.library.Category
import io.github.roberbarras.racha.domain.library.CategoryKey
import io.github.roberbarras.racha.domain.library.LibraryItem
import io.github.roberbarras.racha.domain.library.Mode
import io.github.roberbarras.racha.domain.library.scan.CalendarDate
import io.github.roberbarras.racha.domain.preferences.Settings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlin.random.Random
import kotlin.time.Duration.Companion.days

data class CategorySummary(val category: Category, val itemCount: Int)

/** Everything the Home screen shows (spec §2.2), already filtered by the current Study/Leisure mode (§12.11). */
data class HomeView(
    val mode: Mode,
    val today: CalendarDate,
    val streakDays: Int,
    val categories: List<CategorySummary>,
    /** Opens by default (spec §2.2, §12.2): the primary category when visible in this mode, else the first one. */
    val defaultCategory: CategoryKey?,
    val continueWatching: List<ContinueWatchingEntry>,
    val recommended: List<Recommendation>,
    val newlyDetected: List<ElementInItem>,
    val libraryIsEmpty: Boolean,
)

class ObserveHome(
    private val categories: CategoryRepository,
    private val library: LibraryRepository,
    private val progress: ProgressRepository,
    private val history: HistoryRepository,
    private val preferences: Preferences,
    private val clock: Clock,
    private val localDates: LocalDates,
) {
    private data class Inputs(
        val mode: Mode,
        val categories: List<Category>,
        val counts: Map<CategoryKey, Int>,
        val continueWatching: List<ContinueWatchingEntry>,
        val newElements: List<ElementInItem>,
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(): Flow<HomeView> {
        val since = clock.now() - NEW_FOR
        val inputs = combine(
            preferences.observe(Settings.Mode),
            categories.observeAll(),
            library.observeItemCounts(),
            progress.observeContinueWatching(CONTINUE_LIMIT * 3),
            library.observeNewElements(since, NEW_LIMIT * 3),
        ) { mode, all, counts, continueWatching, newElements -> Inputs(mode, all, counts, continueWatching, newElements) }
        return combine(inputs, history.observeRecent(HISTORY_FOR_STREAK)) { i, recent -> i to recent }
            .mapLatest { (i, recent) ->
                val today = localDates.dateOf(clock.now())
                val visible = i.categories.filter { it.isVisibleIn(i.mode) }
                val visibleKeys = visible.map { it.key }.toSet()
                val continueWatching = i.continueWatching.filter { it.item.categoryKey in visibleKeys }.take(CONTINUE_LIMIT)
                HomeView(
                    mode = i.mode,
                    today = today,
                    streakDays = Streak.current(recent.map { localDates.dateOf(it.watchedAt) }.toSet(), today),
                    categories = visible.map { CategorySummary(it, i.counts[it.key] ?: 0) },
                    defaultCategory = (visible.firstOrNull { it.isPrimary } ?: visible.firstOrNull())?.key,
                    continueWatching = continueWatching,
                    recommended = recommend(visibleKeys, continueWatching, today),
                    newlyDetected = i.newElements.filter { it.item.categoryKey in visibleKeys }.take(NEW_LIMIT),
                    libraryIsEmpty = i.counts.values.sum() == 0,
                )
            }
            .distinctUntilChanged()
    }

    /** Loads only what the rule needs: items in progress, plus a few not-started ones picked with today's seed. */
    private suspend fun recommend(visible: Set<CategoryKey>, continueWatching: List<ContinueWatchingEntry>, today: CalendarDate): List<Recommendation> {
        val items = visible.flatMap { library.items(it) }.filterNot(LibraryItem::archived)
        val inProgress = items.filter { it.progressPct > 0.0 && it.progressPct < 100.0 }
        val notStarted = items.filter { it.progressPct == 0.0 }.shuffled(Random(today.toEpochDay())).take(RECOMMEND_LIMIT)
        val candidates = (inProgress + notStarted).map { item ->
            Recommendations.Candidate(item, library.elements(item.id), progress.forItem(item.id).associateBy { it.elementId })
        }
        return Recommendations.today(candidates, continueWatching.map { it.element.id }.toSet(), today, RECOMMEND_LIMIT)
    }

    private companion object {
        const val CONTINUE_LIMIT = 10
        const val NEW_LIMIT = 8
        const val RECOMMEND_LIMIT = 4
        const val HISTORY_FOR_STREAK = 500
        val NEW_FOR = 7.days
    }
}
