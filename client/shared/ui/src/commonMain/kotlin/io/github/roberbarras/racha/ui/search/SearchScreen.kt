package io.github.roberbarras.racha.ui.search

import io.github.roberbarras.racha.ui.common.ResponsiveColumn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.roberbarras.racha.application.browse.SearchResults
import io.github.roberbarras.racha.application.browse.ContentMatch
import io.github.roberbarras.racha.domain.library.SearchText
import io.github.roberbarras.racha.ui.design.RCard
import io.github.roberbarras.racha.ui.design.RIcon
import io.github.roberbarras.racha.ui.resources.search_in_content
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.roberbarras.racha.domain.library.ContentId
import io.github.roberbarras.racha.ui.app.RachaDependencies
import io.github.roberbarras.racha.ui.common.ElementList
import io.github.roberbarras.racha.ui.common.ItemGrid
import io.github.roberbarras.racha.ui.common.timecode
import io.github.roberbarras.racha.ui.design.MessageState
import io.github.roberbarras.racha.ui.design.RText
import io.github.roberbarras.racha.ui.design.Racha
import io.github.roberbarras.racha.ui.design.RachaIcons
import io.github.roberbarras.racha.ui.design.SearchField
import io.github.roberbarras.racha.ui.design.SectionHeader
import io.github.roberbarras.racha.ui.resources.Res
import io.github.roberbarras.racha.ui.resources.search_elements
import io.github.roberbarras.racha.ui.resources.search_hint
import io.github.roberbarras.racha.ui.resources.search_items
import io.github.roberbarras.racha.ui.resources.search_no_results
import io.github.roberbarras.racha.ui.resources.search_placeholder
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import org.jetbrains.compose.resources.stringResource

@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SearchViewModel(deps: RachaDependencies) : ViewModel() {
    val query = MutableStateFlow("")
    val results: StateFlow<SearchResults?> = query.debounce(200).mapLatest { deps.searchLibrary(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

/** Global search by title (spec §2.2); Ctrl+K opens it on desktop. */
@Composable
fun SearchScreen(deps: RachaDependencies, onOpenItem: (ContentId) -> Unit) {
    val vm = viewModel { SearchViewModel(deps) }
    val query by vm.query.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val categories by remember { deps.categories.observeAll() }.collectAsStateWithLifecycle(emptyList())
    ResponsiveColumn(maxContentWidth = Racha.layout.readingMaxWidth) { _ ->
        item { SearchField(query, { vm.query.value = it }, stringResource(Res.string.search_placeholder), Modifier.fillMaxWidth()) }
        val r = results
        when {
            query.trim().length < 2 -> item { RText(stringResource(Res.string.search_hint), style = Racha.type.body, color = Racha.colors.inkMuted) }
            r != null && r.query == query.trim() && r.isEmpty -> item {
                MessageState(RachaIcons.Search, stringResource(Res.string.search_no_results, query.trim()), "")
            }
            r != null -> {
                if (r.items.isNotEmpty()) item {
                    Column(verticalArrangement = Arrangement.spacedBy(Racha.space.m)) {
                        SectionHeader(stringResource(Res.string.search_items))
                        ItemGrid(r.items, categories.associateBy { it.key }, onOpen = { onOpenItem(it.id) })
                    }
                }
                if (r.elements.isNotEmpty()) item {
                    Column(verticalArrangement = Arrangement.spacedBy(Racha.space.m)) {
                        SectionHeader(stringResource(Res.string.search_elements))
                        ElementList(r.elements, trailing = { it.element.media.durationSec?.let(::timecode) }) { onOpenItem(it.item.id) }
                    }
                }
                if (r.inContent.isNotEmpty()) item {
                    Column(verticalArrangement = Arrangement.spacedBy(Racha.space.m)) {
                        SectionHeader(stringResource(Res.string.search_in_content))
                        r.inContent.forEach { match -> ContentMatchRow(match, query.trim()) { onOpenItem(match.element.item.id) } }
                    }
                }
            }
        }
    }
}

/** A resource found by its text: title, course and the words around the match, the match in bold. */
@Composable
private fun ContentMatchRow(match: ContentMatch, query: String, onOpen: () -> Unit) {
    RCard(Modifier.fillMaxWidth(), onClick = onOpen, onClickLabel = match.element.item.displayTitle) {
        Column(Modifier.padding(Racha.space.l), verticalArrangement = Arrangement.spacedBy(Racha.space.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Racha.space.s)) {
                RIcon(RachaIcons.FileText, null, tint = Racha.colors.inkMuted, size = 18.dp)
                RText(match.element.element.displayTitle, style = Racha.type.bodyStrong, maxLines = 1)
            }
            RText(match.element.item.displayTitle, style = Racha.type.caption, color = Racha.colors.inkMuted, maxLines = 1)
            RText(highlight(match.snippet, query), style = Racha.type.body, color = Racha.colors.inkMuted, maxLines = 3)
        }
    }
}

/** The snippet with the (accent-insensitive) match in bold. */
private fun highlight(snippet: String, query: String): AnnotatedString = buildAnnotatedString {
    append(snippet)
    val at = SearchText.indexIn(SearchText.fold(snippet), SearchText.fold(query))
    if (at >= 0) addStyle(SpanStyle(fontWeight = FontWeight.SemiBold), at, at + query.length)
}
