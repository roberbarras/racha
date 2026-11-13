package io.github.roberbarras.racha.ui.lists

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
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(Racha.space.xxl), verticalArrangement = Arrangement.spacedBy(Racha.space.xl)) {
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
@Composable
fun HistoryScreen(deps: RachaDependencies, onOpenItem: (ContentId) -> Unit) {
    val rows by remember { deps.observeHistory() }.collectAsStateWithLifecycle(null)
    val today = deps.localDates.dateOf(deps.clock.now())
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(Racha.space.xxl), verticalArrangement = Arrangement.spacedBy(Racha.space.xl)) {
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
