package io.github.roberbarras.racha.ui.player

import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.roberbarras.racha.application.playback.PlaybackState
import io.github.roberbarras.racha.ui.common.timecode
import io.github.roberbarras.racha.ui.design.RText
import io.github.roberbarras.racha.ui.design.Racha
import io.github.roberbarras.racha.ui.design.RachaIcons
import io.github.roberbarras.racha.ui.design.interactive
import androidx.compose.foundation.shape.RoundedCornerShape
import io.github.roberbarras.racha.ui.resources.Res
import io.github.roberbarras.racha.ui.resources.ab_loop
import io.github.roberbarras.racha.ui.resources.audio_track
import io.github.roberbarras.racha.ui.resources.back
import io.github.roberbarras.racha.ui.resources.bookmarks
import io.github.roberbarras.racha.ui.resources.elements
import io.github.roberbarras.racha.ui.resources.fullscreen
import io.github.roberbarras.racha.ui.resources.next_element
import io.github.roberbarras.racha.ui.resources.pause
import io.github.roberbarras.racha.ui.resources.picture_in_picture
import io.github.roberbarras.racha.ui.resources.play
import io.github.roberbarras.racha.ui.resources.player_settings
import io.github.roberbarras.racha.ui.resources.previous_element
import io.github.roberbarras.racha.ui.resources.screenshot
import io.github.roberbarras.racha.ui.resources.seek_back
import io.github.roberbarras.racha.ui.resources.seek_forward
import io.github.roberbarras.racha.ui.resources.speed
import io.github.roberbarras.racha.ui.resources.subtitles
import org.jetbrains.compose.resources.stringResource

/**
 * Top bar (back, title, panels and tools), centre transport (previous, −N, play/pause, +N, next) and bottom bar
 * (seek bar, time, speed, fullscreen), over soft scrims so the video stays visible.
 */
@Composable
internal fun PlayerControls(
    vm: PlayerViewModel,
    state: PlaybackState,
    position: Long,
    fullscreen: Boolean,
    panelOpen: Boolean,
    onBack: () -> Unit,
    onInteraction: () -> Unit,
    onScreenshot: () -> Unit,
) {
    val step = vm.preferences.value.seekStep
    fun act(block: () -> Unit) = { onInteraction(); block() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // On a phone the title gets its own row; wider windows keep everything on one line.
        val compact = maxWidth < COMPACT_TOP_BAR
        Column(
            Modifier.align(Alignment.TopStart).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(ControlsScrim, Color.Transparent)))
                .statusBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ControlButton(RachaIcons.ArrowLeft, stringResource(Res.string.back), onBack)
                Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    RText(state.element?.displayTitle.orEmpty(), style = Racha.type.heading, color = Color.White, maxLines = 1)
                    RText(state.item?.displayTitle.orEmpty(), style = Racha.type.caption, color = Color.White.copy(alpha = 0.75f), maxLines = 1)
                }
                if (!compact) Tools(vm, state, panelOpen, ::act, onScreenshot)
            }
            if (compact) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { Tools(vm, state, panelOpen, ::act, onScreenshot) }
        }

        Row(
            Modifier.align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (state.previous != null) ControlButton(RachaIcons.SkipBack, stringResource(Res.string.previous_element), act { vm.previous() })
            ControlButton(RachaIcons.RotateCcw, stringResource(Res.string.seek_back, step), act { vm.controller.seekBy(-step) })
            Box(Modifier.background(Racha.colors.accent, androidx.compose.foundation.shape.CircleShape)) {
                ControlButton(
                    if (state.engine.playing) RachaIcons.Pause else RachaIcons.Play,
                    stringResource(if (state.engine.playing) Res.string.pause else Res.string.play),
                    act { vm.controller.togglePlay() },
                    size = 64.dp,
                )
            }
            ControlButton(RachaIcons.RotateCw, stringResource(Res.string.seek_forward, step), act { vm.controller.seekBy(step) })
            if (state.next != null) ControlButton(RachaIcons.SkipForward, stringResource(Res.string.next_element), act { vm.next() })
        }

        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, ControlsScrim)))
                .navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            SeekBar(state, position, onSeek = { vm.controller.seekTo(it) }, onInteraction = onInteraction)
            Row(verticalAlignment = Alignment.CenterVertically) {
                val duration = state.engine.durationMs
                RText(
                    if (duration != null) "${timecode(position / 1000)} / ${timecode(duration / 1000)}" else timecode(position / 1000),
                    style = with(Racha.type) { label.numeric() },
                    color = Color.White,
                    modifier = Modifier.weight(1f),
                )
                Box(Modifier.width(64.dp)) {
                    ControlButtonText(if (state.holdingSpeed) "${vm.preferences.value.longPressRate}×" else "${trim(state.speed)}×", stringResource(Res.string.speed)) {
                        onInteraction()
                        vm.togglePanel(PlayerPanel.Speed)
                    }
                }
                ControlButton(if (fullscreen) RachaIcons.Minimize else RachaIcons.Maximize, stringResource(Res.string.fullscreen), act { vm.setFullscreen(!fullscreen) })
            }
        }
    }
}

@Composable
private fun ControlButtonText(text: String, description: String, onClick: () -> Unit) {
    Box(
        Modifier.padding(4.dp)
            .interactive(onClick, RoundedCornerShape(8.dp), onClickLabel = description, overlay = Color.White)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) { RText(text, style = with(Racha.type) { label.numeric() }, color = Color.White) }
}

internal fun trim(speed: Double): String = if (speed == speed.toLong().toDouble()) speed.toLong().toString() else speed.toString()

/** Below this width the top bar uses two rows. */
internal val COMPACT_TOP_BAR = 600.dp

/** Height of the top bar, to place badges and notices under it. */
internal fun topBarHeight(maxWidth: Dp): Dp = if (maxWidth < COMPACT_TOP_BAR) 120.dp else 72.dp

@Composable
private fun Tools(vm: PlayerViewModel, state: PlaybackState, panelOpen: Boolean, act: (() -> Unit) -> () -> Unit, onScreenshot: () -> Unit) {
    if (state.subtitles.isNotEmpty()) ControlButton(RachaIcons.Captions, stringResource(Res.string.subtitles), act { vm.togglePanel(PlayerPanel.Subtitles) }, active = state.engine.selectedSubtitle != null)
    if (state.engine.audioTracks.size > 1) ControlButton(RachaIcons.AudioLines, stringResource(Res.string.audio_track), act { vm.togglePanel(PlayerPanel.Audio) })
    ControlButton(RachaIcons.Bookmark, stringResource(Res.string.bookmarks), act { vm.togglePanel(PlayerPanel.Bookmarks) })
    ControlButton(RachaIcons.Repeat, stringResource(Res.string.ab_loop), act {
        when {
            state.loopA == null -> vm.controller.markLoopA()
            state.loopB == null -> vm.controller.markLoopB()
            else -> vm.controller.clearLoop()
        }
    }, active = state.loopA != null)
    ControlButton(RachaIcons.Camera, stringResource(Res.string.screenshot), act(onScreenshot))
    ControlButton(RachaIcons.PictureInPicture2, stringResource(Res.string.picture_in_picture), act { vm.enterPictureInPicture() })
    ControlButton(RachaIcons.Settings, stringResource(Res.string.player_settings), act { vm.togglePanel(PlayerPanel.Settings) })
    ControlButton(RachaIcons.ListVideo, stringResource(Res.string.elements), act { vm.togglePanel(PlayerPanel.Queue) }, active = panelOpen)
}
