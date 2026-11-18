package io.github.roberbarras.racha.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.roberbarras.racha.application.playback.QueueEntry
import io.github.roberbarras.racha.domain.learning.WatchTime
import io.github.roberbarras.racha.domain.library.ContentId
import io.github.roberbarras.racha.domain.library.ElementKind
import io.github.roberbarras.racha.domain.library.Section
import io.github.roberbarras.racha.ui.design.ProgressBar
import io.github.roberbarras.racha.ui.design.RIcon
import io.github.roberbarras.racha.ui.design.RText
import io.github.roberbarras.racha.ui.design.Racha
import io.github.roberbarras.racha.ui.design.interactive
import io.github.roberbarras.racha.ui.resources.Res
import io.github.roberbarras.racha.ui.resources.total_duration
import org.jetbrains.compose.resources.stringResource

/** A row of the side list: a section heading or one of its elements. */
private sealed interface QueueRow {
    val key: String

    data class Heading(val section: Section?, val time: WatchTime) : QueueRow {
        override val key: String get() = "section-" + (section?.id?.value ?: "none")
    }

    data class Entry(val entry: QueueEntry) : QueueRow {
        override val key: String get() = entry.element.id.value
    }
}

/** The elements under their sections, in the course's order; elements of an unknown section go last, unheaded. */
private fun rows(queue: List<QueueEntry>, sections: List<Section>): List<QueueRow> {
    if (sections.isEmpty()) return queue.map(QueueRow::Entry)
    val bySection = queue.groupBy { it.element.sectionId }
    val known = sections.filter { bySection.containsKey(it.id) }
    val out = ArrayList<QueueRow>(queue.size + known.size)
    known.forEach { section ->
        val entries = bySection.getValue(section.id)
        out += QueueRow.Heading(section, WatchTime.of(entries.map { it.element to it.progress }))
        entries.forEach { out += QueueRow.Entry(it) }
    }
    val sectionIds = known.map { it.id }.toSet()
    queue.filter { it.element.sectionId !in sectionIds }.forEach { out += QueueRow.Entry(it) }
    return out
}

/**
 * Every element of the course/series by section, with each section's length and the course's (spec §4.2/§5 right
 * sidebar): the current one highlighted, watched ones on a green tint, progress of the ones half watched. Drawn on
 * the dark rail colours; scrolls to the current element when it opens.
 */
@Composable
fun CourseQueueList(
    queue: List<QueueEntry>,
    current: ContentId?,
    onSelect: (QueueEntry) -> Unit,
    modifier: Modifier = Modifier,
    sections: List<Section> = emptyList(),
) {
    val rows = remember(queue, sections) { rows(queue, sections) }
    val course = remember(queue) { WatchTime.of(queue.map { it.element to it.progress }) }
    val listState = rememberLazyListState()
    LaunchedEffect(current, rows.size) {
        // The course summary is item 0, so a row's index is also the item just above it: a little context shows.
        val index = rows.indexOfFirst { it is QueueRow.Entry && it.entry.element.id == current }
        if (index > 0) listState.scrollToItem(index)
    }
    LazyColumn(modifier, state = listState, verticalArrangement = Arrangement.spacedBy(Racha.space.xs)) {
        item(key = "course") {
            Column(Modifier.padding(bottom = Racha.space.s), verticalArrangement = Arrangement.spacedBy(Racha.space.xxs)) {
                RText(stringResource(Res.string.total_duration, compactDuration(course.totalSec)), style = Racha.type.bodyStrong, color = Racha.colors.onRail)
                watchedAndLeft(course)?.let { RText(it, style = Racha.type.caption, color = Racha.colors.onRailMuted) }
            }
        }
        items(rows, key = { it.key }) { row ->
            when (row) {
                is QueueRow.Heading -> SectionHeading(row)
                is QueueRow.Entry -> EntryRow(row.entry, row.entry.element.id == current, onSelect)
            }
        }
    }
}

@Composable
private fun SectionHeading(row: QueueRow.Heading) {
    Row(
        Modifier.fillMaxWidth().padding(top = Racha.space.m, bottom = Racha.space.xxs, start = Racha.space.xs, end = Racha.space.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Racha.space.s),
    ) {
        RText((row.section?.let { sectionTitle(it) } ?: "").uppercase(), Modifier.weight(1f), style = Racha.type.overline, color = Racha.colors.onRailMuted, maxLines = 2)
        if (row.time.totalSec > 0) RText(compactDuration(row.time.totalSec), style = with(Racha.type) { caption.numeric() }, color = Racha.colors.onRailMuted)
    }
}

@Composable
private fun EntryRow(entry: QueueEntry, isCurrent: Boolean, onSelect: (QueueEntry) -> Unit) {
    val shape = RoundedCornerShape(Racha.radius.m)
    val done = entry.progress?.completed == true
    val background = when {
        isCurrent -> Racha.colors.accent.copy(alpha = 0.22f)
        done -> Racha.colors.success.copy(alpha = 0.16f)
        else -> Color.Transparent
    }
    Row(
        Modifier.fillMaxWidth().alpha(if (entry.element.archived) 0.5f else 1f).clip(shape).background(background)
            .interactive({ onSelect(entry) }, shape, enabled = !entry.element.archived && !isCurrent, overlay = Color.White)
            .padding(Racha.space.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Racha.space.m),
    ) {
        Box(Modifier.size(26.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            RIcon(elementIcon(entry.element), null, tint = if (done) Racha.colors.success else Racha.colors.onRailMuted, size = 14.dp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Racha.space.xxs)) {
            RText(entry.element.displayTitle, style = if (isCurrent) Racha.type.bodyStrong else Racha.type.body, color = Racha.colors.onRail, maxLines = 2)
            val percent = entry.progress?.percent ?: 0.0
            if (!done && percent > 0 && entry.element.kind == ElementKind.VIDEO) ProgressBar((percent / 100).toFloat(), height = 3.dp)
        }
        entry.element.media.durationSec?.let { RText(timecode(it), style = with(Racha.type) { caption.numeric() }, color = Racha.colors.onRailMuted) }
    }
}
