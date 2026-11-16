package io.github.roberbarras.racha.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.roberbarras.racha.application.stats.DayActivity
import io.github.roberbarras.racha.application.stats.PersonalStats
import io.github.roberbarras.racha.ui.app.RachaDependencies
import io.github.roberbarras.racha.ui.common.IconBadge
import io.github.roberbarras.racha.ui.common.ResponsiveColumn
import io.github.roberbarras.racha.ui.common.ScreenTitle
import io.github.roberbarras.racha.ui.common.categoryIcon
import io.github.roberbarras.racha.ui.common.categoryName
import io.github.roberbarras.racha.ui.common.compactDuration
import io.github.roberbarras.racha.ui.design.ButtonKind
import io.github.roberbarras.racha.ui.design.MessageState
import io.github.roberbarras.racha.ui.design.RButton
import io.github.roberbarras.racha.ui.design.RCard
import io.github.roberbarras.racha.ui.design.RIcon
import io.github.roberbarras.racha.ui.design.RText
import io.github.roberbarras.racha.ui.design.Racha
import io.github.roberbarras.racha.ui.design.RachaIcons
import io.github.roberbarras.racha.ui.design.SectionHeader
import io.github.roberbarras.racha.ui.design.SkeletonBlock
import io.github.roberbarras.racha.ui.resources.Res
import io.github.roberbarras.racha.ui.resources.activity_description
import io.github.roberbarras.racha.ui.resources.activity_less
import io.github.roberbarras.racha.ui.resources.activity_more
import io.github.roberbarras.racha.ui.resources.activity_title
import io.github.roberbarras.racha.ui.resources.by_category_title
import io.github.roberbarras.racha.ui.resources.completed_title
import io.github.roberbarras.racha.ui.resources.more_title
import io.github.roberbarras.racha.ui.resources.nav_account
import io.github.roberbarras.racha.ui.resources.nav_folders
import io.github.roberbarras.racha.ui.resources.nav_settings
import io.github.roberbarras.racha.ui.resources.stat_active_days
import io.github.roberbarras.racha.ui.resources.stat_best_streak
import io.github.roberbarras.racha.ui.resources.stat_completed
import io.github.roberbarras.racha.ui.resources.stat_streak
import io.github.roberbarras.racha.ui.resources.stat_total
import io.github.roberbarras.racha.ui.resources.stat_week
import io.github.roberbarras.racha.ui.resources.stats_all_devices
import io.github.roberbarras.racha.ui.resources.stats_empty_message
import io.github.roberbarras.racha.ui.resources.stats_empty_title
import io.github.roberbarras.racha.ui.resources.stats_this_device
import io.github.roberbarras.racha.ui.resources.stats_title
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

class StatsViewModel(private val deps: RachaDependencies) : ViewModel() {
    private val mutableStats = MutableStateFlow<PersonalStats?>(null)
    val stats: StateFlow<PersonalStats?> = mutableStats.asStateFlow()

    /** This device's figures at once, then the account's (all devices) when the server answers. */
    fun load() = viewModelScope.launch {
        mutableStats.value = deps.loadPersonalStats(includeRemote = false)
        if (deps.sync != null) deps.loadPersonalStats(includeRemote = true).takeIf { it.allDevices }?.let { mutableStats.value = it }
    }
}

/**
 * Personal statistics (spec §2.2, §10.3): time watched, streaks, a year of activity and completed courses. On phones
 * it also leads to Folders and Account, which do not fit in the bottom bar.
 */
@Composable
fun StatsScreen(deps: RachaDependencies, onFolders: () -> Unit, onAccount: (() -> Unit)?, onSettings: () -> Unit = {}) {
    val vm = viewModel { StatsViewModel(deps) }
    val stats by vm.stats.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.load() }
    ResponsiveColumn(maxContentWidth = Racha.layout.readingMaxWidth) { compact ->
        val s = stats
        item {
            ScreenTitle(
                stringResource(Res.string.stats_title),
                s?.let { stringResource(if (it.allDevices) Res.string.stats_all_devices else Res.string.stats_this_device) },
            )
        }
        when {
            s == null -> item { SkeletonBlock(Modifier.fillMaxWidth().height(160.dp)) }
            s.totalSeconds == 0L && s.activeDays == 0 && s.completedElements == 0L -> item {
                MessageState(RachaIcons.ChartColumn, stringResource(Res.string.stats_empty_title), stringResource(Res.string.stats_empty_message))
            }
            else -> {
                item(key = "tiles") { Tiles(s) }
                item(key = "activity") { ActivityCard(s) }
                if (s.byCategory.isNotEmpty()) item(key = "categories") { CategoriesCard(s) }
                if (s.completedItems.isNotEmpty()) item(key = "completed") { CompletedCard(s) }
            }
        }
        if (compact) item(key = "more") { MoreLinks(onFolders, onAccount, onSettings) }
    }
}

/** An even grid: as many columns as fit (2 to 6), every tile the same width. */
@Composable
private fun Tiles(s: PersonalStats) {
    val tiles: List<@Composable (Modifier) -> Unit> = listOf(
        { Tile(compactDuration(s.totalSeconds), stringResource(Res.string.stat_total), RachaIcons.Clock, it) },
        { Tile(compactDuration(s.last7DaysSeconds), stringResource(Res.string.stat_week), RachaIcons.Calendar, it) },
        { Tile(s.streakDays.toString(), stringResource(Res.string.stat_streak), RachaIcons.Flame, it, highlight = s.streakDays > 0) },
        { Tile(s.bestStreakDays.toString(), stringResource(Res.string.stat_best_streak), RachaIcons.Flame, it) },
        { Tile(s.activeDays.toString(), stringResource(Res.string.stat_active_days), RachaIcons.CircleCheck, it) },
        { Tile(s.completedElements.toString(), stringResource(Res.string.stat_completed), RachaIcons.Check, it) },
    )
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = Racha.space.m
        val columns = ((maxWidth + gap) / (MIN_TILE_WIDTH + gap)).toInt().coerceIn(2, tiles.size).let { if (it == 4 || it == 5) 3 else it }
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            tiles.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap), modifier = Modifier.height(IntrinsicSize.Max)) {
                    row.forEach { tile -> tile(Modifier.weight(1f).fillMaxHeight()) }
                    repeat(columns - row.size) { Box(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun Tile(value: String, label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, highlight: Boolean = false) {
    RCard(modifier.semantics(mergeDescendants = true) {}) {
        Column(Modifier.padding(Racha.space.l), verticalArrangement = Arrangement.spacedBy(Racha.space.xs)) {
            RIcon(icon, null, tint = if (highlight) Racha.colors.accent else Racha.colors.inkMuted, size = 18.dp)
            RText(value, style = Racha.type.title, maxLines = 1)
            RText(label, style = Racha.type.caption, color = Racha.colors.inkMuted, maxLines = 2)
        }
    }
}

@Composable
private fun ActivityCard(s: PersonalStats) {
    val activeInYear = s.days.count { it.active }
    val description = stringResource(Res.string.activity_description, activeInYear)
    RCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Racha.space.l), verticalArrangement = Arrangement.spacedBy(Racha.space.m)) {
            SectionHeader(stringResource(Res.string.activity_title))
            // Cells grow to fill wide windows (up to a limit); on a phone the year scrolls, starting at today.
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val cell = ((maxWidth + GAP) / WEEKS - GAP).coerceIn(MIN_CELL, MAX_CELL)
                val scroll = rememberScrollState(Int.MAX_VALUE)
                Box(Modifier.fillMaxWidth().horizontalScroll(scroll)) {
                    HeatMap(s.days, cell, Modifier.semantics { contentDescription = description })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Racha.space.s)) {
                RText(description, style = Racha.type.caption, color = Racha.colors.inkMuted, modifier = Modifier.weight(1f))
                RText(stringResource(Res.string.activity_less), style = Racha.type.caption, color = Racha.colors.inkSubtle)
                Legend()
                RText(stringResource(Res.string.activity_more), style = Racha.type.caption, color = Racha.colors.inkSubtle)
            }
        }
    }
}

private val MIN_TILE_WIDTH = 150.dp
private const val WEEKS = 53
private val MIN_CELL = 11.dp
private val MAX_CELL = 18.dp
private val CELL = 12.dp
private val GAP = 3.dp

/** Level 0..4 of a day: nothing, active without measured time, then by minutes watched. */
internal fun level(day: DayActivity): Int = when {
    day.seconds >= 3_600 -> 4
    day.seconds >= 1_800 -> 3
    day.seconds >= 600 -> 2
    day.seconds > 0 || day.active -> 1
    else -> 0
}

@Composable
private fun levelColors(): List<Color> {
    val accent = Racha.colors.accent
    return listOf(Racha.colors.surfaceSunken, accent.copy(alpha = 0.3f), accent.copy(alpha = 0.55f), accent.copy(alpha = 0.8f), accent)
}

/** Weeks as columns (Monday at the top), today in the last one. One canvas: cheap to draw and to scroll. */
@Composable
private fun HeatMap(days: List<DayActivity>, cellSize: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    if (days.isEmpty()) return
    val colors = levelColors()
    val today = days.last().date
    val todayRow = Math.floorMod(today.toEpochDay() + 3, 7L).toInt() // 1970-01-01 was a Thursday; Monday = 0
    val shown = days.takeLast(52 * 7 + todayRow + 1)
    val columns = (shown.size + 6) / 7
    Canvas(modifier.size(width = cellSize * columns + GAP * (columns - 1), height = cellSize * 7 + GAP * 6)) {
        val cell = cellSize.toPx()
        val step = cell + GAP.toPx()
        val radius = CornerRadius(cell / 4)
        shown.forEachIndexed { index, day ->
            drawRoundRect(colors[level(day)], Offset((index / 7) * step, (index % 7) * step), Size(cell, cell), radius)
        }
    }
}

@Composable
private fun Legend() {
    val colors = levelColors()
    Row(horizontalArrangement = Arrangement.spacedBy(GAP)) {
        colors.forEach { Box(Modifier.size(CELL).clip(RoundedCornerShape(3.dp)).background(it)) }
    }
}

@Composable
private fun CategoriesCard(s: PersonalStats) {
    val max = s.byCategory.maxOf { it.seconds }.coerceAtLeast(1)
    RCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Racha.space.l), verticalArrangement = Arrangement.spacedBy(Racha.space.m)) {
            SectionHeader(stringResource(Res.string.by_category_title))
            s.byCategory.forEach { entry ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Racha.space.m)) {
                    IconBadge(categoryIcon(entry.category))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Racha.space.xs)) {
                        Row {
                            RText(categoryName(entry.category), style = Racha.type.bodyStrong, modifier = Modifier.weight(1f), maxLines = 1)
                            RText(compactDuration(entry.seconds), style = Racha.type.body, color = Racha.colors.inkMuted)
                        }
                        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(Racha.radius.pill)).background(Racha.colors.progressTrack)) {
                            Box(
                                Modifier.fillMaxWidth(entry.seconds.toFloat() / max).height(6.dp).clip(RoundedCornerShape(Racha.radius.pill))
                                    .background(Racha.colors.accent),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompletedCard(s: PersonalStats) {
    RCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Racha.space.l), verticalArrangement = Arrangement.spacedBy(Racha.space.s)) {
            SectionHeader(stringResource(Res.string.completed_title))
            s.completedItems.forEach { item ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Racha.space.s)) {
                    RIcon(RachaIcons.CircleCheck, null, tint = Racha.colors.success, size = 18.dp)
                    RText(item.displayTitle, style = Racha.type.body, maxLines = 2)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MoreLinks(onFolders: () -> Unit, onAccount: (() -> Unit)?, onSettings: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Racha.space.s)) {
        SectionHeader(stringResource(Res.string.more_title))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Racha.space.s), verticalArrangement = Arrangement.spacedBy(Racha.space.s)) {
            RButton(stringResource(Res.string.nav_folders), onFolders, kind = ButtonKind.Secondary, icon = RachaIcons.FolderOpen)
            if (onAccount != null) RButton(stringResource(Res.string.nav_account), onAccount, kind = ButtonKind.Secondary, icon = RachaIcons.Cloud)
            RButton(stringResource(Res.string.nav_settings), onSettings, kind = ButtonKind.Secondary, icon = RachaIcons.Settings)
        }
    }
}
