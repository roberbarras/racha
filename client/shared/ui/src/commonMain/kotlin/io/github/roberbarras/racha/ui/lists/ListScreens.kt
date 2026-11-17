package io.github.roberbarras.racha.ui.lists

import io.github.roberbarras.racha.ui.common.ResponsiveColumn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.roberbarras.racha.application.browse.HistoryRow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import io.github.roberbarras.racha.application.library.ElementInItem
import io.github.roberbarras.racha.domain.library.ContentId
import io.github.roberbarras.racha.ui.app.RachaDependencies
import io.github.roberbarras.racha.ui.common.ElementList
import io.github.roberbarras.racha.ui.common.ItemGrid
import io.github.roberbarras.racha.ui.common.ScreenTitle
import io.github.roberbarras.racha.ui.common.longDate
import io.github.roberbarras.racha.ui.common.timecode
import io.github.roberbarras.racha.ui.design.MessageState
import io.github.roberbarras.racha.ui.design.Racha
import io.github.roberbarras.racha.ui.design.RachaIcons
import io.github.roberbarras.racha.ui.design.SectionHeader
import io.github.roberbarras.racha.ui.resources.Res
import io.github.roberbarras.racha.ui.resources.empty_history_message
import io.github.roberbarras.racha.ui.resources.empty_history_title
import io.github.roberbarras.racha.ui.resources.empty_my_list_message
import io.github.roberbarras.racha.ui.resources.empty_my_list_title
import io.github.roberbarras.racha.ui.resources.nav_history
import io.github.roberbarras.racha.ui.resources.nav_my_list
import io.github.roberbarras.racha.ui.resources.today
import io.github.roberbarras.racha.ui.resources.yesterday
import org.jetbrains.compose.resources.stringResource

/** "Mi lista" (spec §2.2). */
@Composable
fun MyListScreen(deps: RachaDependencies, onOpenItem: (ContentId) -> Unit) {
    val list by remember { deps.observeMyList() }.collectAsStateWithLifecycle(null)
    val categories by remember { deps.categories.observeAll() }.collectAsStateWithLifecycle(emptyList())
    val view = list
    ResponsiveColumn(maxContentWidth = Racha.layout.readingMaxWidth) { _ ->
        item { ScreenTitle(stringResource(Res.string.nav_my_list)) }
        if (view != null && view.items.isEmpty() && view.elements.isEmpty()) {
            item { MessageState(RachaIcons.Bookmark, stringResource(Res.string.empty_my_list_title), stringResource(Res.string.empty_my_list_message)) }
        }
        if (view != null && view.items.isNotEmpty()) {
            item { ItemGrid(view.items, categories.associateBy { it.key }, onOpen = { onOpenItem(it.id) }) }
        }
        if (view != null && view.elements.isNotEmpty()) {
            item { ElementList(view.elements, trailing = { it.element.media.durationSec?.let(::timecode) }) { onOpenItem(it.item.id) } }
        }
    }
}

/** "Historial" (spec §2.2), grouped by local day. */
/** History rows kept by the screen's view model (database work off the composition, like the other lists). */
class HistoryViewModel(deps: RachaDependencies) : ViewModel() {
    val rows: StateFlow<List<HistoryRow>?> = deps.observeHistory().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@Composable
fun HistoryScreen(deps: RachaDependencies, onOpenItem: (ContentId) -> Unit) {
    val vm = viewModel { HistoryViewModel(deps) }
    val rows by vm.rows.collectAsStateWithLifecycle()
    val today = deps.localDates.dateOf(deps.clock.now())
    ResponsiveColumn(maxContentWidth = Racha.layout.readingMaxWidth) { _ ->
        item { ScreenTitle(stringResource(Res.string.nav_history)) }
        val history = rows
        if (history != null && history.isEmpty()) {
            item { MessageState(RachaIcons.History, stringResource(Res.string.empty_history_title), stringResource(Res.string.empty_history_message)) }
        }
        history.orEmpty().groupBy { deps.localDates.dateOf(it.entry.watchedAt) }.forEach { (day, entries) ->
            item(key = day.toString()) {
                Column(verticalArrangement = Arrangement.spacedBy(Racha.space.m)) {
                    SectionHeader(
                        when (day) {
                            today -> stringResource(Res.string.today)
                            today.plusDays(-1) -> stringResource(Res.string.yesterday)
                            else -> longDate(day)
                        },
                    )
                    ElementList(
                        entries.map { ElementInItem(it.element, it.item) },
                        trailing = { row -> entries.first { it.element.id == row.element.id }.entry.positionAtEndSec.let(::timecode) },
                    ) { onOpenItem(it.item.id) }
                }
            }
        }
    }
}
