package io.github.roberbarras.racha.application.browse

import io.github.roberbarras.racha.domain.library.ContentId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** What the Android widget shows (spec §10.5): the streak, today's turn, how far that course is, and "continue". */
data class TodayCard(
    val streakDays: Int,
    val elementTitle: String?,
    val itemTitle: String?,
    /** 0..100, of the course/series of [elementTitle]. */
    val itemProgressPct: Int,
    /** Opened by "continue" (resume where it was left). */
    val element: ContentId?,
    val libraryIsEmpty: Boolean,
)

/** From Home's data: what is half-watched first, else today's recommendation. Emits only when something changes. */
class ObserveTodayCard(private val observeHome: ObserveHome) {
    operator fun invoke(): Flow<TodayCard> = observeHome().map { home ->
        val continuing = home.continueWatching.firstOrNull()
        val next = continuing?.let { it.item to it.element } ?: home.recommended.firstOrNull()?.let { it.item to it.element }
        TodayCard(
            streakDays = home.streakDays,
            elementTitle = next?.second?.displayTitle,
            itemTitle = next?.first?.displayTitle,
            itemProgressPct = next?.first?.progressPct?.toInt() ?: 0,
            element = next?.second?.id,
            libraryIsEmpty = home.libraryIsEmpty,
        )
    }.distinctUntilChanged()
}
