package io.github.roberbarras.racha.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.roberbarras.racha.application.learning.ContinueWatchingEntry
import io.github.roberbarras.racha.domain.library.ContentId
import io.github.roberbarras.racha.domain.playback.StartMode
import io.github.roberbarras.racha.ui.app.RachaDependencies
import io.github.roberbarras.racha.ui.common.elementIcon
import io.github.roberbarras.racha.ui.common.timecode
import io.github.roberbarras.racha.ui.design.Poster
import io.github.roberbarras.racha.ui.design.ProgressBar
import io.github.roberbarras.racha.ui.design.RIcon
import io.github.roberbarras.racha.ui.design.RText
import io.github.roberbarras.racha.ui.design.Racha
import io.github.roberbarras.racha.ui.design.RachaIcons
import io.github.roberbarras.racha.ui.design.interactive
import io.github.roberbarras.racha.ui.resources.Res
import io.github.roberbarras.racha.ui.resources.continue_watching
import io.github.roberbarras.racha.ui.resources.resume_from
import io.github.roberbarras.racha.ui.resources.start_over
import io.github.roberbarras.racha.ui.resources.up_next
import io.github.roberbarras.racha.ui.resources.watched_percent
import org.jetbrains.compose.resources.stringResource

/** Right pane on wide layouts: the selected "continue watching" element, resume/start over, and what follows. */
@Composable
fun ResumePane(
    entry: ContinueWatchingEntry,
    deps: RachaDependencies,
    onPlay: (ContentId, StartMode) -> Unit,
    onOpenItem: (ContentId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Racha.colors
    val detail by remember(entry.item.id) { deps.observeItemDetail(entry.item.id) }.collectAsStateWithLifecycle(null)
    val upcoming = detail?.sections?.flatMap { it.elements }
        ?.dropWhile { it.element.id != entry.element.id }?.drop(1)
        ?.filter { !it.watched && !it.element.archived }?.take(3).orEmpty()
    val duration = entry.progress.durationSec ?: entry.element.media.durationSec
    Column(
        modifier.fillMaxHeight().padding(Racha.space.l).clip(RoundedCornerShape(Racha.radius.xl)).background(colors.rail)
            .verticalScroll(rememberScrollState()).padding(Racha.space.xl),
        verticalArrangement = Arrangement.spacedBy(Racha.space.l),
    ) {
        RText(stringResource(Res.string.continue_watching).uppercase(), style = Racha.type.overline, color = colors.onRailMuted)
        RText(
            entry.item.displayTitle,
            modifier = Modifier.interactive({ onOpenItem(entry.item.id) }, RoundedCornerShape(Racha.radius.s), overlay = colors.onRail),
            style = Racha.type.title,
            color = colors.onRail,
            maxLines = 3,
        )
        Poster(entry.item.posterPath, entry.item.displayTitle) {
            Box(
                Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
                    .padding(Racha.space.m),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(Racha.space.xs)) {
                    Row {
                        RText(
                            if (duration != null) "${timecode(entry.progress.positionSec)} / ${timecode(duration)}" else timecode(entry.progress.positionSec),
                            style = with(Racha.type) { label.numeric() },
                            color = Color.White,
                            modifier = Modifier.weight(1f),
                        )
                        RText(stringResource(Res.string.watched_percent, entry.progress.percent.toInt()), style = Racha.type.label, color = Color.White)
                    }
                    ProgressBar((entry.progress.percent / 100).toFloat())
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(Racha.space.xs)) {
            RText(entry.element.displayTitle, style = Racha.type.heading, color = colors.onRail, maxLines = 2)
        }
        PaneButton(stringResource(Res.string.resume_from, timecode(entry.progress.positionSec)), filled = true) { onPlay(entry.element.id, StartMode.RESUME) }
        PaneButton(stringResource(Res.string.start_over), filled = false) { onPlay(entry.element.id, StartMode.BEGINNING) }
        if (upcoming.isNotEmpty()) {
            RText(stringResource(Res.string.up_next).uppercase(), style = Racha.type.overline, color = colors.onRailMuted, modifier = Modifier.padding(top = Racha.space.m))
            upcoming.forEach { next ->
                Row(
                    Modifier.fillMaxWidth().interactive({ onPlay(next.element.id, StartMode.RESUME) }, RoundedCornerShape(Racha.radius.s), overlay = colors.onRail)
                        .padding(vertical = Racha.space.s),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Racha.space.m),
                ) {
                    RIcon(elementIcon(next.element), null, tint = colors.onRailMuted, size = 18.dp)
                    RText(next.element.displayTitle, style = Racha.type.body, color = colors.onRail, maxLines = 1, modifier = Modifier.weight(1f))
                    next.element.media.durationSec?.let { RText(timecode(it), style = with(Racha.type) { caption.numeric() }, color = colors.onRailMuted) }
                }
            }
        }
    }
}

@Composable
private fun PaneButton(text: String, filled: Boolean, onClick: () -> Unit) {
    val colors = Racha.colors
    val shape = RoundedCornerShape(Racha.radius.pill)
    Box(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(shape).background(if (filled) colors.accent else Color.Transparent)
            .border(1.5.dp, if (filled) Color.Transparent else colors.onRail.copy(alpha = 0.6f), shape)
            .interactive(onClick, shape, overlay = colors.onRail).padding(Racha.space.m),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Racha.space.s)) {
            RIcon(if (filled) RachaIcons.Play else RachaIcons.RotateCcw, null, tint = if (filled) colors.onAccent else colors.onRail, size = 18.dp)
            RText(text, style = Racha.type.bodyStrong, color = if (filled) colors.onAccent else colors.onRail)
        }
    }
}
