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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.roberbarras.racha.application.playback.QueueEntry
import io.github.roberbarras.racha.domain.library.ContentId
import io.github.roberbarras.racha.domain.library.ElementKind
import io.github.roberbarras.racha.ui.design.ProgressBar
import io.github.roberbarras.racha.ui.design.RIcon
import io.github.roberbarras.racha.ui.design.RText
import io.github.roberbarras.racha.ui.design.Racha
import io.github.roberbarras.racha.ui.design.RachaIcons
import io.github.roberbarras.racha.ui.design.interactive

/**
 * Every element of the course/series, the current one highlighted, with per-element progress (spec §4.2/§5 right
 * sidebar). Drawn on the dark rail colours; scrolls to the current element when it opens.
 */
@Composable
fun CourseQueueList(queue: List<QueueEntry>, current: ContentId?, onSelect: (QueueEntry) -> Unit, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(current, queue.size) {
        val index = queue.indexOfFirst { it.element.id == current }
        if (index > 0) listState.scrollToItem((index - 1).coerceAtLeast(0))
    }
    LazyColumn(modifier, state = listState, verticalArrangement = Arrangement.spacedBy(Racha.space.xs)) {
        items(queue, key = { it.element.id.value }) { entry ->
            val isCurrent = entry.element.id == current
            val shape = RoundedCornerShape(Racha.radius.m)
            Row(
                Modifier.fillMaxWidth().alpha(if (entry.element.archived) 0.5f else 1f).clip(shape)
                    .background(if (isCurrent) Racha.colors.accent.copy(alpha = 0.22f) else Color.Transparent)
                    .interactive({ onSelect(entry) }, shape, enabled = !entry.element.archived && !isCurrent, overlay = Color.White)
                    .padding(Racha.space.s),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Racha.space.m),
            ) {
                val done = entry.progress?.completed == true
                Box(Modifier.size(26.dp).clip(CircleShape).background(if (done) Racha.colors.success else Color.White.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                    if (done) RIcon(RachaIcons.Check, null, tint = Color.White, size = 14.dp)
                    else RIcon(elementIcon(entry.element), null, tint = Racha.colors.onRailMuted, size = 14.dp)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Racha.space.xxs)) {
                    RText(entry.element.displayTitle, style = if (isCurrent) Racha.type.bodyStrong else Racha.type.body, color = Racha.colors.onRail, maxLines = 2)
                    val percent = entry.progress?.percent ?: 0.0
                    if (!done && percent > 0 && entry.element.kind == ElementKind.VIDEO) ProgressBar((percent / 100).toFloat(), height = 3.dp)
                }
                entry.element.media.durationSec?.let { RText(timecode(it), style = with(Racha.type) { caption.numeric() }, color = Racha.colors.onRailMuted) }
            }
        }
    }
}
