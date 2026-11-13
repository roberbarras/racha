package io.github.roberbarras.racha.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.roberbarras.racha.application.playback.PlaybackState
import io.github.roberbarras.racha.domain.library.ContentId
import io.github.roberbarras.racha.domain.library.ElementKind
import io.github.roberbarras.racha.domain.library.PlayableElement
import io.github.roberbarras.racha.domain.playback.StartMode
import io.github.roberbarras.racha.domain.preferences.VideoScale
import io.github.roberbarras.racha.ui.app.RachaDependencies
import io.github.roberbarras.racha.ui.common.timecode
import io.github.roberbarras.racha.ui.design.RButton
import io.github.roberbarras.racha.ui.design.ButtonKind
import io.github.roberbarras.racha.ui.design.RIcon
import io.github.roberbarras.racha.ui.design.RText
import io.github.roberbarras.racha.ui.design.Racha
import io.github.roberbarras.racha.ui.design.RachaIcons
import io.github.roberbarras.racha.ui.design.interactive
import io.github.roberbarras.racha.ui.resources.Res
import io.github.roberbarras.racha.ui.resources.back
import io.github.roberbarras.racha.ui.resources.course_finished
import io.github.roberbarras.racha.ui.resources.loop_a_set
import io.github.roberbarras.racha.ui.resources.loop_label
import io.github.roberbarras.racha.ui.resources.player_unavailable
import io.github.roberbarras.racha.ui.resources.resume_from
import io.github.roberbarras.racha.ui.resources.screenshot_saved
import io.github.roberbarras.racha.ui.resources.start_over
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/**
 * Full-screen player (spec §4.2): video, gestures, auto-hiding controls, right sidebar and panels. In
 * picture-in-picture only the video is drawn. [onOpenResource] receives a resource reached by "next"/autoplay.
 */
@Composable
fun PlayerScreen(
    deps: RachaDependencies,
    hooks: PlayerHooks,
    element: ContentId,
    mode: StartMode,
    onBack: () -> Unit,
    onOpenResource: (PlayableElement) -> Unit,
) {
    val vm = viewModel(key = "player-${element.value}") { PlayerViewModel(deps, hooks, element, mode) }
    val state by vm.state.collectAsStateWithLifecycle()
    val inPip by hooks.inPictureInPicture.collectAsStateWithLifecycle()
    val prefs by vm.preferences.collectAsStateWithLifecycle()
    val panel by vm.panel.collectAsStateWithLifecycle()
    val fullscreen by vm.fullscreen.collectAsStateWithLifecycle()
    val scale by vm.scale.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // Leaving the app (not into picture-in-picture) pauses and saves (spec §4.3).
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && !hooks.inPictureInPicture.value) vm.onBackground()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(state.openResource) {
        state.openResource?.let {
            vm.controller.consumeOpenResource()
            onOpenResource(it)
        }
    }
    if (state.element?.kind == ElementKind.RESOURCE) {
        LaunchedEffect(state.element) { state.element?.let(onOpenResource) }
        return
    }

    var controlsVisible by remember { mutableStateOf(true) }
    var lastInteraction by remember { mutableLongStateOf(0L) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var scrubTarget by remember { mutableStateOf<Long?>(null) }
    var brightness by remember { mutableFloatStateOf(hooks.brightness()) }
    var volume by remember { mutableFloatStateOf(hooks.volume()) }
    var position by remember { mutableLongStateOf(0L) }
    fun touch() {
        controlsVisible = true
        lastInteraction++
    }

    // The position is read 4× per second only while the controls are on screen.
    LaunchedEffect(controlsVisible, state.element?.id) {
        while (controlsVisible) {
            position = vm.engine.positionMs()
            delay(250)
        }
    }
    // Controls hide after 3 s of playing without interaction.
    LaunchedEffect(lastInteraction, state.engine.playing, panel) {
        if (state.engine.playing && panel == PlayerPanel.None) {
            delay(3_000)
            controlsVisible = false
        }
    }
    LaunchedEffect(feedback) {
        if (feedback != null) {
            delay(900)
            feedback = null
        }
    }
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(2_500)
            vm.notice.value = null
        }
    }

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color.Black)
            .focusRequester(focus).focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                touch()
                when (event.key) {
                    Key.Spacebar, Key.K -> vm.controller.togglePlay()
                    Key.DirectionLeft, Key.J -> vm.controller.seekBy(-prefs.seekStep)
                    Key.DirectionRight, Key.L -> vm.controller.seekBy(prefs.seekStep)
                    Key.F -> vm.setFullscreen(!fullscreen)
                    Key.N -> vm.next()
                    Key.P -> vm.previous()
                    Key.Escape -> if (fullscreen) vm.setFullscreen(false) else onBack()
                    else -> return@onKeyEvent false
                }
                true
            },
    ) {
        val container = this
        VideoBox(vm, hooks, state, scale, container.maxWidth, container.maxHeight)
        state.engine.subtitleText?.let { SubtitleText(it, Modifier.align(Alignment.BottomCenter).padding(bottom = if (controlsVisible && !inPip) 120.dp else 40.dp)) }
        if (inPip) return@BoxWithConstraints

        Box(
            Modifier.fillMaxSize().playerGestures(prefs, scope) { action ->
                when (action) {
                    GestureAction.ToggleControls -> if (controlsVisible) controlsVisible = false else touch()
                    is GestureAction.SeekBy -> {
                        vm.controller.seekBy(action.seconds)
                        feedback = (if (action.seconds > 0) "+" else "−") + "${kotlin.math.abs(action.seconds)} s"
                    }
                    is GestureAction.Scrub -> {
                        val base = vm.engine.positionMs()
                        val target = (base + action.seconds * 1000L).coerceAtLeast(0)
                        if (action.finished) {
                            vm.controller.seekTo(target)
                            scrubTarget = null
                        } else {
                            scrubTarget = target
                            feedback = timecode(target / 1000)
                        }
                    }
                    is GestureAction.Brightness -> {
                        brightness = (brightness + action.delta).coerceIn(0.02f, 1f)
                        hooks.setBrightness(brightness)
                        feedback = "${(brightness * 100).toInt()} %"
                    }
                    is GestureAction.Volume -> {
                        volume = (volume + action.delta).coerceIn(0f, 1f)
                        hooks.setVolume(volume)
                        feedback = "${(volume * 100).toInt()} %"
                    }
                    is GestureAction.HoldSpeed -> {
                        vm.controller.holdSpeed(action.held)
                        feedback = if (action.held) "${prefs.longPressRate}×" else null
                    }
                    is GestureAction.Pinch -> vm.scale.value = if (action.zoomIn) VideoScale.FILL else VideoScale.FIT
                }
            },
        )

        feedback?.let { Feedback(it, Modifier.align(Alignment.Center)) }

        AnimatedVisibility(
            visible = controlsVisible || !state.engine.playing,
            enter = fadeIn(tween(Racha.duration(Racha.motion.fast))),
            exit = fadeOut(tween(Racha.duration(Racha.motion.standard))),
        ) {
            PlayerControls(
                vm = vm,
                state = state,
                position = scrubTarget ?: position,
                fullscreen = fullscreen,
                panelOpen = panel != PlayerPanel.None,
                onBack = onBack,
                onInteraction = ::touch,
                onScreenshot = { vm.screenshot { path -> getString(Res.string.screenshot_saved, path) } },
            )
        }

        if (panel != PlayerPanel.None) {
            PlayerPanelHost(
                vm = vm,
                state = state,
                panel = panel,
                compact = container.maxWidth < Racha.layout.compactMaxWidth,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }

        state.resumeOffer?.let { offer -> ResumePrompt(offer, vm, Modifier.align(Alignment.Center)) }
        if (state.reachedEnd) EndCard(onBack, Modifier.align(Alignment.Center))
        if (state.unavailable) Unavailable(onBack, Modifier.align(Alignment.Center))
        notice?.let { Notice(it, Modifier.align(Alignment.TopCenter).padding(top = 72.dp)) }
        if (state.loopA != null) LoopBadge(state, Modifier.align(Alignment.TopStart).padding(top = 72.dp, start = 16.dp))
    }
}

@Composable
private fun VideoBox(vm: PlayerViewModel, hooks: PlayerHooks, state: PlaybackState, scale: VideoScale, maxWidth: Dp, maxHeight: Dp) {
    val w = state.engine.videoWidth
    val h = state.engine.videoHeight
    val (width, height) = if (w <= 0 || h <= 0 || scale == VideoScale.STRETCH) {
        maxWidth to maxHeight
    } else {
        val video = w.toFloat() / h
        val box = maxWidth / maxHeight
        val fitWidth = if (video > box) maxWidth else maxHeight * video
        val fitHeight = if (video > box) maxWidth / video else maxHeight
        if (scale == VideoScale.FIT) {
            fitWidth to fitHeight
        } else {
            val factor = maxOf(maxWidth / fitWidth, maxHeight / fitHeight)
            fitWidth * factor to fitHeight * factor
        }
    }
    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(0.dp)), contentAlignment = Alignment.Center) {
        hooks.VideoSurface(vm.engine, Modifier.requiredSize(width, height))
    }
}

@Composable
private fun SubtitleText(text: String, modifier: Modifier) {
    RText(
        text,
        modifier.widthIn(max = 720.dp).padding(horizontal = 24.dp).clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 10.dp, vertical = 4.dp),
        style = Racha.type.heading,
        color = Color.White,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun Feedback(text: String, modifier: Modifier) {
    RText(
        text,
        modifier.clip(RoundedCornerShape(Racha.radius.l)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 20.dp, vertical = 12.dp),
        style = with(Racha.type) { title.numeric() },
        color = Color.White,
    )
}

@Composable
private fun Notice(text: String, modifier: Modifier) {
    RText(
        text,
        modifier.widthIn(max = 520.dp).clip(RoundedCornerShape(Racha.radius.pill)).background(Racha.colors.surfaceRaised)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        style = Racha.type.label,
        maxLines = 2,
    )
}

@Composable
private fun LoopBadge(state: PlaybackState, modifier: Modifier) {
    val text = if (state.loopB == null) stringResource(Res.string.loop_a_set, timecode(state.loopA!! / 1000))
    else stringResource(Res.string.loop_label, timecode(state.loopA!! / 1000), timecode(state.loopB!! / 1000))
    RText(
        text,
        modifier.clip(RoundedCornerShape(Racha.radius.pill)).background(Racha.colors.accent).padding(horizontal = 12.dp, vertical = 6.dp),
        style = with(Racha.type) { label.numeric() },
        color = Racha.colors.onAccent,
    )
}

@Composable
private fun ResumePrompt(offer: Long, vm: PlayerViewModel, modifier: Modifier) {
    Column(
        modifier.widthIn(max = 360.dp).clip(RoundedCornerShape(Racha.radius.xl)).background(Racha.colors.rail).padding(Racha.space.xl),
        verticalArrangement = Arrangement.spacedBy(Racha.space.m),
    ) {
        RText(vm.state.value.element?.displayTitle.orEmpty(), style = Racha.type.heading, color = Racha.colors.onRail, maxLines = 2)
        RButton(stringResource(Res.string.resume_from, timecode(offer)), { vm.answerResume(true) }, Modifier.fillMaxWidth(), icon = RachaIcons.Play)
        RButton(stringResource(Res.string.start_over), { vm.answerResume(false) }, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary, icon = RachaIcons.RotateCcw)
    }
}

@Composable
private fun EndCard(onBack: () -> Unit, modifier: Modifier) {
    Column(
        modifier.widthIn(max = 360.dp).clip(RoundedCornerShape(Racha.radius.xl)).background(Racha.colors.rail).padding(Racha.space.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Racha.space.m),
    ) {
        RIcon(RachaIcons.CircleCheck, null, tint = Racha.colors.accent, size = 36.dp)
        RText(stringResource(Res.string.course_finished), style = Racha.type.title, color = Racha.colors.onRail, textAlign = TextAlign.Center)
        RButton(stringResource(Res.string.back), onBack, icon = RachaIcons.ArrowLeft)
    }
}

@Composable
private fun Unavailable(onBack: () -> Unit, modifier: Modifier) {
    Column(
        modifier.widthIn(max = 360.dp).clip(RoundedCornerShape(Racha.radius.xl)).background(Racha.colors.rail).padding(Racha.space.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Racha.space.m),
    ) {
        RIcon(RachaIcons.HardDrive, null, tint = Racha.colors.onRailMuted, size = 32.dp)
        RText(stringResource(Res.string.player_unavailable), style = Racha.type.body, color = Racha.colors.onRail, textAlign = TextAlign.Center)
        RButton(stringResource(Res.string.back), onBack, icon = RachaIcons.ArrowLeft)
    }
}

/** Seek bar with A-B and bookmark marks; tap or drag to seek. */
@Composable
internal fun SeekBar(state: PlaybackState, position: Long, onSeek: (Long) -> Unit, onInteraction: () -> Unit, modifier: Modifier = Modifier) {
    val duration = state.engine.durationMs ?: 0L
    val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val shown = dragFraction ?: fraction
    BoxWithConstraints(
        modifier.fillMaxWidth().height(28.dp)
            .pointerInput(duration) {
                detectTapGestures { offset ->
                    onInteraction()
                    if (duration > 0) onSeek((offset.x / size.width * duration).toLong())
                }
            }
            .pointerInput(duration) {
                detectHorizontalDragGestures(
                    onDragStart = { offset -> onInteraction(); dragFraction = offset.x / size.width },
                    onDragEnd = { dragFraction?.let { if (duration > 0) onSeek((it * duration).toLong()) }; dragFraction = null },
                    onDragCancel = { dragFraction = null },
                ) { change, _ ->
                    onInteraction()
                    dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        val width = maxWidth
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.25f)))
        val loopA = state.loopA
        if (duration > 0 && loopA != null) {
            val a = loopA.toFloat() / duration
            val b = (state.loopB ?: position).toFloat() / duration
            Box(Modifier.offset(x = width * a).width(width * (b - a).coerceAtLeast(0f)).height(4.dp).background(Racha.colors.accent.copy(alpha = 0.45f)))
        }
        Box(Modifier.width(width * shown).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Racha.colors.accent))
        if (duration > 0) {
            state.bookmarks.forEach { bookmark ->
                Box(Modifier.offset(x = width * (bookmark.timestampSec * 1000f / duration) - 1.dp).width(2.dp).height(10.dp).background(Color.White))
            }
        }
        Box(Modifier.offset(x = width * shown - 7.dp).size(14.dp).clip(CircleShape).background(Racha.colors.accent))
    }
}

@Composable
internal fun ControlButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, size: Dp = 44.dp, active: Boolean = false) {
    Box(
        Modifier.size(size).clip(CircleShape).background(if (active) Racha.colors.accent.copy(alpha = 0.3f) else Color.Transparent)
            .interactive(onClick, CircleShape, overlay = Color.White),
        contentAlignment = Alignment.Center,
    ) { RIcon(icon, label, tint = Color.White, size = size * 0.5f) }
}

internal val ControlsScrim = Color.Black.copy(alpha = 0.45f)
