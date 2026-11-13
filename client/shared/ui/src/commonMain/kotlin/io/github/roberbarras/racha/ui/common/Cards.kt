package io.github.roberbarras.racha.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.roberbarras.racha.application.learning.ContinueWatchingEntry
import io.github.roberbarras.racha.application.library.ElementInItem
import io.github.roberbarras.racha.domain.learning.Recommendation
import io.github.roberbarras.racha.domain.learning.RecommendationReason
import io.github.roberbarras.racha.domain.library.LibraryItem
import io.github.roberbarras.racha.ui.design.ProgressBar
import io.github.roberbarras.racha.ui.design.RCard
import io.github.roberbarras.racha.ui.design.RIcon
import io.github.roberbarras.racha.ui.design.RText
import io.github.roberbarras.racha.ui.design.Racha
import io.github.roberbarras.racha.ui.design.Poster
import io.github.roberbarras.racha.ui.design.Tag
import io.github.roberbarras.racha.ui.design.TagTone
import io.github.roberbarras.racha.ui.resources.Res
import io.github.roberbarras.racha.ui.resources.discover
import io.github.roberbarras.racha.ui.resources.elements_progress
import io.github.roberbarras.racha.ui.resources.items_count
import io.github.roberbarras.racha.ui.resources.next_lesson
import io.github.roberbarras.racha.ui.resources.today
import io.github.roberbarras.racha.ui.resources.unavailable
import io.github.roberbarras.racha.ui.resources.up_next
import org.jetbrains.compose.resources.stringResource

/** "Continue watching" card: frame, lesson, course, progress and `position / duration` (spec §2.2, §12.2). */
@Composable
fun ContinueCard(entry: ContinueWatchingEntry, upNext: Boolean, selected: Boolean, width: Dp, onClick: () -> Unit) {
    val duration = entry.progress.durationSec ?: entry.element.media.durationSec
    RCard(Modifier.width(width), onClick = onClick, selected = selected) {
        Box(Modifier.padding(Racha.space.s)) {
            Poster(entry.item.posterPath, entry.item.displayTitle) {
                if (upNext) Tag(stringResource(Res.string.up_next), Modifier.align(Alignment.TopStart).padding(Racha.space.s), TagTone.Accent)
            }
        }
        Column(Modifier.padding(horizontal = Racha.space.m).padding(bottom = Racha.space.m), verticalArrangement = Arrangement.spacedBy(Racha.space.xs)) {
            RText(entry.element.displayTitle, style = Racha.type.heading, maxLines = 1)
            RText(entry.item.displayTitle, style = Racha.type.caption, color = Racha.colors.inkMuted, maxLines = 1)
            ProgressBar((entry.progress.percent / 100).toFloat(), Modifier.padding(top = Racha.space.xs))
            RText(
                if (duration != null) "${timecode(entry.progress.positionSec)} / ${timecode(duration)}" else timecode(entry.progress.positionSec),
                modifier = Modifier.fillMaxWidth(),
                style = with(Racha.type) { caption.numeric() },
                color = Racha.colors.inkMuted,
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
            )
        }
    }
}

@Composable
fun RecommendationCard(recommendation: Recommendation, width: Dp, onClick: () -> Unit) {
    RCard(Modifier.width(width), onClick = onClick) {
        Column(Modifier.padding(Racha.space.m), verticalArrangement = Arrangement.spacedBy(Racha.space.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Tag(
                    stringResource(if (recommendation.reason == RecommendationReason.NEXT_LESSON) Res.string.next_lesson else Res.string.discover),
                    tone = if (recommendation.reason == RecommendationReason.NEXT_LESSON) TagTone.Accent else TagTone.Neutral,
                )
                Box(Modifier.weight(1f))
                RText(stringResource(Res.string.today), style = Racha.type.caption, color = Racha.colors.inkSubtle)
            }
            RText(recommendation.element.displayTitle, style = Racha.type.heading, maxLines = 2)
            RText(recommendation.item.displayTitle, style = Racha.type.caption, color = Racha.colors.inkMuted, maxLines = 1)
        }
    }
}

/** One row of "Newly detected": kind icon, lesson, course, size, duration, when it appeared. */
@Composable
fun NewElementRow(entry: ElementInItem, minutesAgo: Long, compact: Boolean = false) {
    val element = entry.element
    val details = listOfNotNull(fileSize(element.fileSize), element.media.durationSec?.let(::timecode)).joinToString(" · ")
    Row(
        Modifier.fillMaxWidth().background(Racha.colors.surface).padding(horizontal = Racha.space.l, vertical = Racha.space.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Racha.space.m),
    ) {
        RIcon(elementIcon(element), null, tint = Racha.colors.inkMuted)
        Column(Modifier.weight(1f)) {
            RText(element.displayTitle, style = Racha.type.bodyStrong, maxLines = 1)
            RText(entry.item.displayTitle, style = Racha.type.caption, color = Racha.colors.inkMuted, maxLines = 1)
            if (compact) RText("$details · ${detectedAgo(minutesAgo)}", style = with(Racha.type) { caption.numeric() }, color = Racha.colors.inkSubtle, maxLines = 1)
        }
        if (!compact) {
            RText(details, style = with(Racha.type) { caption.numeric() }, color = Racha.colors.inkMuted)
            RText(detectedAgo(minutesAgo), style = Racha.type.caption, color = Racha.colors.inkSubtle)
        }
    }
}

/** Course/series card for library grids, My list and search. Archived items are dimmed and tagged. */
@Composable
fun ItemCard(item: LibraryItem, title: String, tint: androidx.compose.ui.graphics.Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    RCard(modifier.alpha(if (item.archived) 0.6f else 1f), onClick = onClick) {
        Box(Modifier.padding(Racha.space.s)) {
            Poster(item.posterPath, title, tint = tint) {
                if (item.archived) Tag(stringResource(Res.string.unavailable), Modifier.align(Alignment.TopStart).padding(Racha.space.s))
            }
        }
        Column(Modifier.padding(horizontal = Racha.space.m).padding(bottom = Racha.space.m), verticalArrangement = Arrangement.spacedBy(Racha.space.xs)) {
            RText(title, style = Racha.type.heading, maxLines = 2)
            val meta = listOfNotNull(
                stringResource(Res.string.items_count, item.totalItems),
                item.totalDurationSec.takeIf { it > 0 }?.let(::compactDuration),
            ).joinToString(" · ")
            RText(meta, style = Racha.type.caption, color = Racha.colors.inkMuted, maxLines = 1)
            if (item.progressPct > 0) ProgressBar((item.progressPct / 100).toFloat(), completed = item.progressPct >= 100.0)
        }
    }
}

/** Small square badge with an icon, used in rails and list rows. */
@Composable
fun IconBadge(icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier) {
    Box(
        modifier.size(36.dp).clip(RoundedCornerShape(Racha.radius.s)).background(Racha.colors.accentSoft),
        contentAlignment = Alignment.Center,
    ) { RIcon(icon, null, tint = Racha.colors.accent, size = 18.dp) }
}

@Composable
fun elementsProgressLabel(watched: Int, total: Int) = stringResource(Res.string.elements_progress, watched, total)
