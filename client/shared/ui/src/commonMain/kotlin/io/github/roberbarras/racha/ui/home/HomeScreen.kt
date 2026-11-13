package io.github.roberbarras.racha.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.roberbarras.racha.application.browse.HomeView
import io.github.roberbarras.racha.application.learning.ContinueWatchingEntry
import io.github.roberbarras.racha.domain.library.CategoryKey
import io.github.roberbarras.racha.domain.library.ContentId
import io.github.roberbarras.racha.domain.library.LibraryItem
import io.github.roberbarras.racha.domain.library.Mode
import io.github.roberbarras.racha.ui.app.AppViewModel
import io.github.roberbarras.racha.ui.app.RachaDependencies
import io.github.roberbarras.racha.ui.common.ContinueCard
import io.github.roberbarras.racha.ui.common.ItemGrid
import io.github.roberbarras.racha.ui.common.NewElementRow
import io.github.roberbarras.racha.ui.common.RecommendationCard
import io.github.roberbarras.racha.ui.common.StorageAccessBanner
import io.github.roberbarras.racha.ui.common.categoryName
import io.github.roberbarras.racha.ui.common.longDate
import io.github.roberbarras.racha.ui.design.MessageState
import io.github.roberbarras.racha.ui.design.RText
import io.github.roberbarras.racha.ui.design.Racha
import io.github.roberbarras.racha.ui.design.RachaIcons
import io.github.roberbarras.racha.ui.design.SectionHeader
import io.github.roberbarras.racha.ui.design.SegmentedToggle
import io.github.roberbarras.racha.ui.design.SkeletonBlock
import io.github.roberbarras.racha.ui.design.interactive
import io.github.roberbarras.racha.ui.resources.Res
import io.github.roberbarras.racha.ui.resources.add_folder
import io.github.roberbarras.racha.ui.resources.continue_watching
import io.github.roberbarras.racha.ui.resources.empty_category
import io.github.roberbarras.racha.ui.resources.empty_continue
import io.github.roberbarras.racha.ui.resources.empty_library_message
import io.github.roberbarras.racha.ui.resources.empty_library_title
import io.github.roberbarras.racha.ui.resources.greeting
import io.github.roberbarras.racha.ui.resources.library
import io.github.roberbarras.racha.ui.resources.mode_leisure
import io.github.roberbarras.racha.ui.resources.mode_study
import io.github.roberbarras.racha.ui.resources.newly_detected
import io.github.roberbarras.racha.ui.resources.recommended_today
import org.jetbrains.compose.resources.stringResource

/**
 * Home (spec §2.2): greeting and mode, "Continue watching", "Recommended today", "Newly detected" and the library
 * by category tabs opened on the primary category (§12.2). Wide layouts add the resume pane on the right.
 */
@Composable
fun HomeScreen(
    app: AppViewModel,
    deps: RachaDependencies,
    onOpenItem: (ContentId) -> Unit,
    onPlay: (ContentId, Boolean) -> Unit,
    onAddFolder: () -> Unit,
) {
    val view by app.home.collectAsStateWithLifecycle()
    val storageAccess by app.storageAccess.collectAsStateWithLifecycle()
    val home = view
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val withPane = maxWidth >= 840.dp && home != null && home.continueWatching.isNotEmpty()
        val compact = maxWidth < Racha.layout.compactMaxWidth
        var selected by remember { mutableStateOf<ContentId?>(null) }
        val selectedEntry = home?.continueWatching?.firstOrNull { it.element.id == selected } ?: home?.continueWatching?.firstOrNull()
        Row(Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.weight(1f).fillMaxHeight(),
                contentPadding = PaddingValues(horizontal = if (compact) Racha.space.l else Racha.space.xxl, vertical = Racha.space.xl),
                verticalArrangement = Arrangement.spacedBy(Racha.space.xl),
            ) {
                item { Header(home, compact, onMode = { app.setMode(it) }) }
                if (!storageAccess) item { StorageAccessBanner(onAllow = app::requestStorageAccess) }
                when {
                    home == null -> item { LoadingRows() }
                    home.libraryIsEmpty -> item {
                        MessageState(
                            RachaIcons.FolderPlus,
                            stringResource(Res.string.empty_library_title),
                            stringResource(Res.string.empty_library_message),
                            actionLabel = stringResource(Res.string.add_folder),
                            onAction = onAddFolder,
                        )
                    }
                    else -> {
                        item {
                            ContinueRow(home.continueWatching, selectedEntry?.element?.id.takeIf { withPane }) { entry ->
                                if (withPane) selected = entry.element.id else onPlay(entry.element.id, false)
                            }
                        }
                        if (home.recommended.isNotEmpty()) item {
                            Column(verticalArrangement = Arrangement.spacedBy(Racha.space.m)) {
                                SectionHeader(stringResource(Res.string.recommended_today))
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(Racha.space.l)) {
                                    items(home.recommended, key = { it.element.id.value }) { r ->
                                        RecommendationCard(r, Racha.layout.cardWidth) { onOpenItem(r.item.id) }
                                    }
                                }
                            }
                        }
                        if (home.newlyDetected.isNotEmpty()) item { NewlyDetected(home, deps.clock.now(), compact) { onOpenItem(it) } }
                        item { LibraryTabs(home, deps, onOpenItem) }
                    }
                }
            }
            if (withPane && selectedEntry != null) {
                ResumePane(selectedEntry, deps, onPlay = onPlay, onOpenItem = onOpenItem, modifier = Modifier.width(Racha.layout.detailPaneWidth))
            }
        }
    }
}

private fun Modifier.width(dp: androidx.compose.ui.unit.Dp) = this.then(Modifier.widthIn(min = dp, max = dp))

@Composable
private fun Header(home: HomeView?, compact: Boolean, onMode: (Mode) -> Unit) {
    val greeting = @Composable {
        Column {
            RText(stringResource(Res.string.greeting), style = if (compact) Racha.type.title else Racha.type.display)
            if (home != null) RText(longDate(home.today), style = Racha.type.body, color = Racha.colors.inkMuted)
        }
    }
    val toggle = @Composable {
        SegmentedToggle(
            listOf(stringResource(Res.string.mode_study) to Mode.STUDY, stringResource(Res.string.mode_leisure) to Mode.LEISURE),
            selected = home?.mode ?: Mode.STUDY,
            onSelect = onMode,
        )
    }
    if (compact) {
        Column(verticalArrangement = Arrangement.spacedBy(Racha.space.m)) { greeting(); toggle() }
    } else {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Box(Modifier.weight(1f)) { greeting() }
            toggle()
        }
    }
}

@Composable
private fun ContinueRow(entries: List<ContinueWatchingEntry>, selected: ContentId?, onSelect: (ContinueWatchingEntry) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Racha.space.m)) {
        SectionHeader(stringResource(Res.string.continue_watching))
        if (entries.isEmpty()) {
            RText(stringResource(Res.string.empty_continue), style = Racha.type.body, color = Racha.colors.inkMuted)
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(Racha.space.l)) {
                items(entries, key = { it.element.id.value }) { entry ->
                    ContinueCard(entry, upNext = entry == entries.first(), selected = entry.element.id == selected, width = Racha.layout.cardWidth) {
                        onSelect(entry)
                    }
                }
            }
        }
    }
}

@Composable
private fun NewlyDetected(home: HomeView, now: kotlin.time.Instant, compact: Boolean, onOpenItem: (ContentId) -> Unit) {
    val nowMs = now.toEpochMilliseconds()
    Column(verticalArrangement = Arrangement.spacedBy(Racha.space.m)) {
        SectionHeader("${stringResource(Res.string.newly_detected)} · ${home.newlyDetected.size}")
        val shape = RoundedCornerShape(Racha.radius.l)
        Column(Modifier.clip(shape).border(1.dp, Racha.colors.border, shape)) {
            home.newlyDetected.forEachIndexed { index, entry ->
                if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Racha.colors.border))
                Box(Modifier.fillMaxWidth().interactive({ onOpenItem(entry.item.id) })) {
                    NewElementRow(entry, (nowMs - entry.element.firstSeenAt.toEpochMilliseconds()) / 60_000, compact)
                }
            }
        }
    }
}

@Composable
private fun LibraryTabs(home: HomeView, deps: RachaDependencies, onOpenItem: (ContentId) -> Unit) {
    var selectedKey by rememberSaveable(home.mode) { mutableStateOf(home.defaultCategory?.value) }
    LaunchedEffect(home.defaultCategory) { if (selectedKey == null) selectedKey = home.defaultCategory?.value }
    val key = selectedKey?.let(::CategoryKey) ?: return
    val categoryView by remember(key) { deps.observeCategory(key) }.collectAsStateWithLifecycle(null)
    val byKey = home.categories.associate { it.category.key to it.category }
    Column(verticalArrangement = Arrangement.spacedBy(Racha.space.m)) {
        SectionHeader(stringResource(Res.string.library))
        SegmentedToggle(home.categories.map { categoryName(it.category) to it.category.key.value }, selectedKey ?: "", { selectedKey = it })
        val items: List<LibraryItem> = categoryView?.items.orEmpty()
        if (categoryView != null && items.isEmpty()) {
            RText(stringResource(Res.string.empty_category), style = Racha.type.body, color = Racha.colors.inkMuted)
        } else {
            ItemGrid(items, byKey, onOpen = { onOpenItem(it.id) })
        }
    }
}

@Composable
private fun LoadingRows() {
    Column(verticalArrangement = Arrangement.spacedBy(Racha.space.l)) {
        repeat(2) {
            Row(horizontalArrangement = Arrangement.spacedBy(Racha.space.l)) {
                repeat(3) { SkeletonBlock(Modifier.widthIn(min = 220.dp, max = 220.dp).padding(bottom = 120.dp)) }
            }
        }
    }
}
