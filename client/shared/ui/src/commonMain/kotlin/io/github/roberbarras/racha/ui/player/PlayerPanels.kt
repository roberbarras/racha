package io.github.roberbarras.racha.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import io.github.roberbarras.racha.application.playback.PlaybackState
import io.github.roberbarras.racha.domain.library.ElementKind
import io.github.roberbarras.racha.ui.common.elementIcon
import io.github.roberbarras.racha.ui.common.timecode
import io.github.roberbarras.racha.ui.design.ButtonKind
import io.github.roberbarras.racha.ui.design.ProgressBar
import io.github.roberbarras.racha.ui.design.RButton
import io.github.roberbarras.racha.ui.design.RIcon
import io.github.roberbarras.racha.ui.design.RIconButton
import io.github.roberbarras.racha.ui.design.RText
import io.github.roberbarras.racha.ui.design.Racha
import io.github.roberbarras.racha.ui.design.RachaIcons
import io.github.roberbarras.racha.ui.design.interactive
import io.github.roberbarras.racha.ui.resources.Res
import io.github.roberbarras.racha.ui.resources.add_bookmark
import io.github.roberbarras.racha.ui.resources.audio_track
import io.github.roberbarras.racha.ui.resources.bookmark_note
import io.github.roberbarras.racha.ui.resources.bookmarks
import io.github.roberbarras.racha.ui.resources.close
import io.github.roberbarras.racha.ui.resources.elements
import io.github.roberbarras.racha.ui.resources.no_bookmarks
import io.github.roberbarras.racha.ui.resources.player_settings
import io.github.roberbarras.racha.ui.resources.skip_intro
import io.github.roberbarras.racha.ui.resources.skip_outro
import io.github.roberbarras.racha.ui.resources.skip_seconds
import io.github.roberbarras.racha.ui.resources.speed
import io.github.roberbarras.racha.ui.resources.subtitles
import io.github.roberbarras.racha.ui.resources.subtitles_off
import io.github.roberbarras.racha.ui.resources.track_default
import io.github.roberbarras.racha.ui.resources.delete_bookmark
import org.jetbrains.compose.resources.stringResource

/** Right side panel (spec §4.2 "barra lateral derecha desplegable" and the player pickers). */
@Composable
internal fun PlayerPanelHost(vm: PlayerViewModel, state: PlaybackState, panel: PlayerPanel, compact: Boolean, modifier: Modifier) {
    val title = when (panel) {
        PlayerPanel.Queue -> Res.string.elements
        PlayerPanel.Speed -> Res.string.speed
        PlayerPanel.Subtitles -> Res.string.subtitles
        PlayerPanel.Audio -> Res.string.audio_track
        PlayerPanel.Bookmarks -> Res.string.bookmarks
        PlayerPanel.Settings, PlayerPanel.None -> Res.string.player_settings
    }
    Column(
        modifier.fillMaxHeight().then(if (compact) Modifier.fillMaxWidth() else Modifier.width(360.dp))
            .background(Racha.colors.rail.copy(alpha = 0.96f)).statusBarsPadding().padding(Racha.space.l),
        verticalArrangement = Arrangement.spacedBy(Racha.space.m),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RText(stringResource(title), style = Racha.type.title, color = Racha.colors.onRail, modifier = Modifier.weight(1f))
            RIconButton(RachaIcons.X, stringResource(Res.string.close), { vm.panel.value = PlayerPanel.None }, tint = Racha.colors.onRail)
        }
        when (panel) {
            PlayerPanel.Queue -> QueuePanel(vm, state)
            PlayerPanel.Speed -> Options(state.speedSteps.map { "${trim(it)}×" to it }, state.speed) { vm.controller.setSpeed(it) }
            PlayerPanel.Subtitles -> Options(
                listOf(stringResource(Res.string.subtitles_off) to null) + state.subtitles.map { it.language.value.uppercase() to it },
                state.subtitles.firstOrNull { it.id.value == state.engine.selectedSubtitle },
            ) { vm.controller.selectSubtitle(it) }
            PlayerPanel.Audio -> Options(
                state.engine.audioTracks.mapIndexed { i, t -> (t.label ?: t.language?.uppercase() ?: stringResource(Res.string.track_default, i + 1)) to t.id },
                state.engine.selectedAudio,
            ) { vm.controller.selectAudio(it) }
            PlayerPanel.Bookmarks -> BookmarksPanel(vm, state)
            PlayerPanel.Settings -> SkipSettings(vm, state)
            PlayerPanel.None -> Unit
        }
    }
}

@Composable
private fun QueuePanel(vm: PlayerViewModel, state: PlaybackState) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(Racha.space.xs)) {
        items(state.queue, key = { it.element.id.value }) { entry ->
            val current = entry.element.id == state.element?.id
            val shape = RoundedCornerShape(Racha.radius.m)
            Row(
                Modifier.fillMaxWidth().alpha(if (entry.element.archived) 0.5f else 1f).clip(shape)
                    .background(if (current) Racha.colors.accent.copy(alpha = 0.22f) else Color.Transparent)
                    .interactive({ vm.jumpTo(entry.element.id) }, shape, enabled = !entry.element.archived, overlay = Color.White)
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
                    RText(entry.element.displayTitle, style = if (current) Racha.type.bodyStrong else Racha.type.body, color = Racha.colors.onRail, maxLines = 2)
                    val percent = entry.progress?.percent ?: 0.0
                    if (!done && percent > 0 && entry.element.kind == ElementKind.VIDEO) ProgressBar((percent / 100).toFloat(), height = 3.dp)
                }
                entry.element.media.durationSec?.let { RText(timecode(it), style = with(Racha.type) { caption.numeric() }, color = Racha.colors.onRailMuted) }
            }
        }
    }
}

@Composable
private fun <T> Options(options: List<Pair<String, T>>, selected: T?, onSelect: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Racha.space.xs)) {
        options.forEach { (label, value) ->
            val isSelected = value == selected
            val shape = RoundedCornerShape(Racha.radius.m)
            Row(
                Modifier.fillMaxWidth().clip(shape).background(if (isSelected) Racha.colors.accent.copy(alpha = 0.22f) else Color.Transparent)
                    .interactive({ onSelect(value) }, shape, overlay = Color.White).padding(Racha.space.m),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RText(label, style = with(Racha.type) { body.numeric() }, color = Racha.colors.onRail, modifier = Modifier.weight(1f))
                if (isSelected) RIcon(RachaIcons.Check, null, tint = Racha.colors.accent, size = 18.dp)
            }
        }
    }
}

@Composable
private fun BookmarksPanel(vm: PlayerViewModel, state: PlaybackState) {
    var note by remember { mutableStateOf("") }
    val shape = RoundedCornerShape(Racha.radius.m)
    Column(verticalArrangement = Arrangement.spacedBy(Racha.space.m)) {
        Box(Modifier.fillMaxWidth().clip(shape).background(Color.White.copy(alpha = 0.08f)).padding(Racha.space.m)) {
            if (note.isEmpty()) RText(stringResource(Res.string.bookmark_note), style = Racha.type.body, color = Racha.colors.onRailMuted)
            BasicTextField(
                note, { note = it },
                textStyle = Racha.type.body.merge(TextStyle(color = Racha.colors.onRail)),
                cursorBrush = SolidColor(Racha.colors.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        RButton(stringResource(Res.string.add_bookmark), { vm.addBookmark(note); note = "" }, Modifier.fillMaxWidth(), icon = RachaIcons.Plus)
        if (state.bookmarks.isEmpty()) RText(stringResource(Res.string.no_bookmarks), style = Racha.type.body, color = Racha.colors.onRailMuted)
        state.bookmarks.forEach { bookmark ->
            Row(
                Modifier.fillMaxWidth().clip(shape).interactive({ vm.controller.seekTo(bookmark.timestampSec * 1000) }, shape, overlay = Color.White).padding(Racha.space.s),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Racha.space.m),
            ) {
                RText(timecode(bookmark.timestampSec), style = with(Racha.type) { label.numeric() }, color = Racha.colors.accent)
                RText(bookmark.note.ifEmpty { "—" }, style = Racha.type.body, color = Racha.colors.onRail, modifier = Modifier.weight(1f), maxLines = 2)
                RIconButton(RachaIcons.Trash2, stringResource(Res.string.delete_bookmark), { vm.deleteBookmark(bookmark.id) }, tint = Racha.colors.onRailMuted)
            }
        }
    }
}

/** Per-element skip intro/outro (spec §4.2): stepper in 5 s increments, empty = global setting. */
@Composable
private fun SkipSettings(vm: PlayerViewModel, state: PlaybackState) {
    val element = state.element ?: return
    Column(verticalArrangement = Arrangement.spacedBy(Racha.space.l)) {
        SkipRow(stringResource(Res.string.skip_intro), element.skipIntroSec ?: 0) { vm.setSkips(it, element.skipOutroSec) }
        SkipRow(stringResource(Res.string.skip_outro), element.skipOutroSec ?: 0) { vm.setSkips(element.skipIntroSec, it) }
    }
}

@Composable
private fun SkipRow(label: String, seconds: Int, onChange: (Int?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Racha.space.xs)) {
        RText(label, style = Racha.type.label, color = Racha.colors.onRailMuted)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Racha.space.m)) {
            RButton("−5", { onChange((seconds - 5).coerceAtLeast(0).takeIf { it > 0 }) }, kind = ButtonKind.Secondary)
            RText(stringResource(Res.string.skip_seconds, seconds), style = with(Racha.type) { heading.numeric() }, color = Racha.colors.onRail, modifier = Modifier.width(80.dp))
            RButton("+5", { onChange((seconds + 5).coerceAtMost(MAX_SKIP_SEC)) }, kind = ButtonKind.Secondary)
        }
    }
}

/** Same bound as the global skip intro/outro settings. */
private const val MAX_SKIP_SEC = 600
