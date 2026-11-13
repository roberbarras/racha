package io.github.roberbarras.racha.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.roberbarras.racha.application.browse.ElementView
import io.github.roberbarras.racha.application.browse.ItemDetailView
import io.github.roberbarras.racha.application.browse.SectionView
import io.github.roberbarras.racha.domain.learning.FavoriteTarget
import io.github.roberbarras.racha.domain.library.ContentId
import io.github.roberbarras.racha.domain.library.ElementKind
import io.github.roberbarras.racha.domain.library.ResourceType
import io.github.roberbarras.racha.domain.library.SectionKind
import io.github.roberbarras.racha.ui.app.RachaDependencies
import io.github.roberbarras.racha.ui.common.categoryName
import io.github.roberbarras.racha.ui.common.compactDuration
import io.github.roberbarras.racha.ui.common.elementIcon
import io.github.roberbarras.racha.ui.common.elementsProgressLabel
import io.github.roberbarras.racha.ui.common.itemTitle
import io.github.roberbarras.racha.ui.common.parseColor
import io.github.roberbarras.racha.ui.common.timecode
import io.github.roberbarras.racha.ui.design.ButtonKind
import io.github.roberbarras.racha.ui.design.MessageState
import io.github.roberbarras.racha.ui.design.Poster
import io.github.roberbarras.racha.ui.design.ProgressBar
import io.github.roberbarras.racha.ui.design.RButton
import io.github.roberbarras.racha.ui.design.RIcon
import io.github.roberbarras.racha.ui.design.RIconButton
import io.github.roberbarras.racha.ui.design.RText
import io.github.roberbarras.racha.ui.design.Racha
import io.github.roberbarras.racha.ui.design.RachaIcons
import io.github.roberbarras.racha.ui.design.SectionHeader
import io.github.roberbarras.racha.ui.design.SkeletonBlock
import io.github.roberbarras.racha.ui.design.Tag
import io.github.roberbarras.racha.ui.design.TagTone
import io.github.roberbarras.racha.ui.design.interactive
import io.github.roberbarras.racha.ui.resources.Res
import io.github.roberbarras.racha.ui.resources.add_to_my_list
import io.github.roberbarras.racha.ui.resources.back
import io.github.roberbarras.racha.ui.resources.in_my_list
import io.github.roberbarras.racha.ui.resources.kind_html
import io.github.roberbarras.racha.ui.resources.kind_pdf
import io.github.roberbarras.racha.ui.resources.kind_text
import io.github.roberbarras.racha.ui.resources.kind_url
import io.github.roberbarras.racha.ui.resources.kind_video
import io.github.roberbarras.racha.ui.resources.mark_all_unwatched
import io.github.roberbarras.racha.ui.resources.mark_all_watched
import io.github.roberbarras.racha.ui.resources.mark_section_watched
import io.github.roberbarras.racha.ui.resources.mark_unwatched
import io.github.roberbarras.racha.ui.resources.mark_up_to_here
import io.github.roberbarras.racha.ui.resources.mark_watched
import io.github.roberbarras.racha.ui.resources.more_actions
import io.github.roberbarras.racha.ui.resources.resume_from
import io.github.roberbarras.racha.ui.resources.season_n
import io.github.roberbarras.racha.ui.resources.start
import io.github.roberbarras.racha.ui.resources.total_duration
import io.github.roberbarras.racha.ui.resources.unavailable
import io.github.roberbarras.racha.ui.resources.loose_files
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

class DetailViewModel(private val deps: RachaDependencies, private val id: ContentId) : ViewModel() {
    val state: StateFlow<ItemDetailView?> = deps.observeItemDetail(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun toggleFavorite(favorite: Boolean) = viewModelScope.launch { deps.toggleFavorite(FavoriteTarget.Item(id), favorite) }
    fun markItem(watched: Boolean) = viewModelScope.launch { deps.markProgress.item(id, watched) }
    fun markSection(section: ContentId) = viewModelScope.launch { deps.markProgress.section(id, section, watched = true) }
    fun markElement(element: ContentId, watched: Boolean) = viewModelScope.launch { deps.markProgress.element(element, watched) }
    fun markUpTo(element: ContentId) = viewModelScope.launch { deps.markProgress.upTo(id, element) }
}

/** Course/series/channel detail (spec §2.2): sections, per-element progress, manual marks, my list. */
@Composable
fun DetailScreen(deps: RachaDependencies, id: ContentId, onBack: () -> Unit, onPlay: (ContentId, Boolean) -> Unit) {
    val vm = viewModel(key = id.value) { DetailViewModel(deps, id) }
    val detail by vm.state.collectAsStateWithLifecycle()
    val view = detail
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxWidth < Racha.layout.compactMaxWidth
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = if (compact) Racha.space.l else Racha.space.xxl, vertical = Racha.space.xl),
            verticalArrangement = Arrangement.spacedBy(Racha.space.xl),
        ) {
            item { RIconButton(RachaIcons.ArrowLeft, stringResource(Res.string.back), onBack) }
            if (view == null) {
                item { SkeletonBlock(Modifier.fillMaxWidth().height(280.dp)) }
            } else {
                item { Hero(view, vm, onPlay) }
                view.sections.forEach { section ->
                    item(key = section.section.id.value) { SectionBlock(section, vm, compact, onPlay) }
                }
            }
        }
    }
}

@Composable
private fun Hero(view: ItemDetailView, vm: DetailViewModel, onPlay: (ContentId, Boolean) -> Unit) {
    val tint = view.category?.color?.let(::parseColor) ?: Racha.colors.accent
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = maxWidth >= 760.dp
        val poster = @Composable { Poster(view.item.posterPath, itemTitle(view.item), Modifier.widthIn(max = if (wide) 420.dp else maxWidth), tint = tint) }
        val info = @Composable {
            Column(verticalArrangement = Arrangement.spacedBy(Racha.space.m)) {
                view.category?.let { Tag(categoryName(it), tone = TagTone.Accent) }
                RText(itemTitle(view.item), style = Racha.type.display)
                view.item.description?.let { RText(it, style = Racha.type.body, color = Racha.colors.inkMuted) }
                Row(horizontalArrangement = Arrangement.spacedBy(Racha.space.l)) {
                    RText(elementsProgressLabel(view.watchedCount, view.totalCount), style = Racha.type.label, color = Racha.colors.inkMuted)
                    if (view.item.totalDurationSec > 0) {
                        RText(stringResource(Res.string.total_duration, compactDuration(view.item.totalDurationSec)), style = Racha.type.label, color = Racha.colors.inkMuted)
                    }
                }
                ProgressBar((view.item.progressPct / 100).toFloat(), Modifier.widthIn(max = 420.dp), height = 6.dp, completed = view.item.progressPct >= 100)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Racha.space.s), verticalArrangement = Arrangement.spacedBy(Racha.space.s)) {
                    view.nextUp?.let { next ->
                        val position = next.progress?.positionSec ?: 0
                        RButton(
                            if (position > 0) stringResource(Res.string.resume_from, timecode(position)) else stringResource(Res.string.start),
                            onClick = { onPlay(next.element.id, false) },
                            icon = RachaIcons.Play,
                        )
                    }
                    RButton(
                        stringResource(if (view.isFavorite) Res.string.in_my_list else Res.string.add_to_my_list),
                        onClick = { vm.toggleFavorite(!view.isFavorite) },
                        kind = ButtonKind.Secondary,
                        icon = if (view.isFavorite) RachaIcons.BookmarkCheck else RachaIcons.Bookmark,
                    )
                    val allWatched = view.totalCount > 0 && view.watchedCount == view.totalCount
                    RButton(
                        stringResource(if (allWatched) Res.string.mark_all_unwatched else Res.string.mark_all_watched),
                        onClick = { vm.markItem(!allWatched) },
                        kind = ButtonKind.Ghost,
                        icon = RachaIcons.CircleCheck,
                    )
                }
            }
        }
        if (wide) {
            Row(horizontalArrangement = Arrangement.spacedBy(Racha.space.xxl)) {
                Box(Modifier.width(420.dp)) { poster() }
                Box(Modifier.weight(1f)) { info() }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(Racha.space.l)) { poster(); info() }
        }
    }
}

@Composable
private fun SectionBlock(section: SectionView, vm: DetailViewModel, compact: Boolean, onPlay: (ContentId, Boolean) -> Unit) {
    val title = when {
        section.section.titleOverride != null -> section.section.displayTitle
        section.section.kind == SectionKind.SEASON && section.section.title.isEmpty() -> stringResource(Res.string.season_n, section.section.order)
        section.section.title.isEmpty() -> stringResource(Res.string.loose_files)
        else -> section.section.title
    }
    Column(verticalArrangement = Arrangement.spacedBy(Racha.space.m)) {
        SectionHeader(title) {
            if (compact) {
                RIconButton(RachaIcons.CircleCheck, stringResource(Res.string.mark_section_watched), { vm.markSection(section.section.id) }, tint = Racha.colors.inkMuted)
            } else {
                RButton(stringResource(Res.string.mark_section_watched), onClick = { vm.markSection(section.section.id) }, kind = ButtonKind.Ghost)
            }
        }
        val shape = RoundedCornerShape(Racha.radius.l)
        Column(Modifier.clip(shape).background(Racha.colors.surface).border(1.dp, Racha.colors.border, shape)) {
            section.elements.forEachIndexed { index, element ->
                if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Racha.colors.border))
                ElementRow(element, onPlay = { onPlay(element.element.id, false) }, vm = vm)
            }
        }
    }
}

@Composable
private fun ElementRow(view: ElementView, onPlay: () -> Unit, vm: DetailViewModel) {
    val element = view.element
    val colors = Racha.colors
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().alpha(if (element.archived) 0.55f else 1f)
            .interactive(onPlay, enabled = !element.archived).padding(horizontal = Racha.space.l, vertical = Racha.space.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Racha.space.m),
    ) {
        Box(
            Modifier.size(32.dp).clip(CircleShape).background(if (view.watched) colors.success.copy(alpha = 0.15f) else colors.surfaceSunken),
            contentAlignment = Alignment.Center,
        ) {
            if (view.watched) RIcon(RachaIcons.Check, null, tint = colors.success, size = 16.dp)
            else RText(element.effectiveOrder.toString(), style = with(Racha.type) { label.numeric() }, color = colors.inkMuted)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Racha.space.xxs)) {
            RText(element.displayTitle, style = Racha.type.bodyStrong, maxLines = 2)
            Row(horizontalArrangement = Arrangement.spacedBy(Racha.space.s), verticalAlignment = Alignment.CenterVertically) {
                RIcon(elementIcon(element), null, tint = colors.inkSubtle, size = 14.dp)
                RText(kindLabel(view), style = Racha.type.caption, color = colors.inkSubtle)
                if (element.archived) Tag(stringResource(Res.string.unavailable))
            }
            val position = view.progress?.positionSec ?: 0
            if (!view.watched && position > 0) ProgressBar((view.progress!!.percent / 100).toFloat(), Modifier.widthIn(max = 240.dp).padding(top = Racha.space.xs))
        }
        element.media.durationSec?.let { RText(timecode(it), style = with(Racha.type) { caption.numeric() }, color = colors.inkMuted) }
        Box {
            RIconButton(RachaIcons.Menu, stringResource(Res.string.more_actions), { menu = true }, tint = colors.inkMuted)
            if (menu) {
                Popup(alignment = Alignment.TopEnd, onDismissRequest = { menu = false }, properties = PopupProperties(focusable = true)) {
                    val shape = RoundedCornerShape(Racha.radius.m)
                    Column(Modifier.widthIn(min = 220.dp).clip(shape).background(colors.surfaceRaised).border(1.dp, colors.border, shape).padding(Racha.space.xs)) {
                        MenuItem(stringResource(if (view.watched) Res.string.mark_unwatched else Res.string.mark_watched)) {
                            vm.markElement(element.id, !view.watched); menu = false
                        }
                        MenuItem(stringResource(Res.string.mark_up_to_here)) { vm.markUpTo(element.id); menu = false }
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuItem(text: String, onClick: () -> Unit) {
    RText(
        text,
        Modifier.fillMaxWidth().interactive(onClick, RoundedCornerShape(Racha.radius.s)).padding(horizontal = Racha.space.m, vertical = Racha.space.s),
        style = Racha.type.body,
    )
}

@Composable
private fun kindLabel(view: ElementView): String = when {
    view.element.kind == ElementKind.VIDEO -> stringResource(Res.string.kind_video)
    view.element.resourceType == ResourceType.HTML -> stringResource(Res.string.kind_html)
    view.element.resourceType == ResourceType.URL -> stringResource(Res.string.kind_url)
    view.element.resourceType == ResourceType.PDF -> stringResource(Res.string.kind_pdf)
    else -> stringResource(Res.string.kind_text)
}
