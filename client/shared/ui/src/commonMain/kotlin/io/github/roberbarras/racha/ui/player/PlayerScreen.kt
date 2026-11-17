package io.github.roberbarras.racha.ui.player

import io.github.roberbarras.racha.ui.resources.audio_only_hint
import io.github.roberbarras.racha.ui.design.Poster
import io.github.roberbarras.racha.ui.resources.subtitles_off
import io.github.roberbarras.racha.ui.resources.player_subtitles
import io.github.roberbarras.racha.ui.resources.player_muted
import io.github.roberbarras.racha.ui.resources.player_bookmark_added
import io.github.roberbarras.racha.domain.playback.PlayerAction
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.foundation.layout.Spacer
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
import io.github.roberbarras.racha.application.cast.CastState
import io.github.roberbarras.racha.ui.resources.cast_connecting
import io.github.roberbarras.racha.ui.resources.cast_playing_on
import io.github.roberbarras.racha.ui.resources.cast_remote_hint
import io.github.roberbarras.racha.ui.resources.cast_stop
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
            // Leaving the app pauses, except in PiP or audio only (that is what they are for).
            if (event == Lifecycle.Event.ON_STOP && !hooks.inPictureInPicture.value && !vm.audioOnly.value) vm.onBackground()
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
    var mutedFrom by remember { mutableStateOf<Float?>(null) }
    var help by remember { mutableStateOf(false) }
    val mutedText = stringResource(Res.string.player_muted)
    val bookmarkText = stringResource(Res.string.player_bookmark_added)
    val subtitlesOffText = stringResource(Res.string.subtitles_off)
    val subtitlesTemplate = stringResource(Res.string.player_subtitles, "%s")
    fun setVolume(value: Float) {
        volume = value.coerceIn(0f, 1f)
        hooks.setVolume(volume)
        feedback = if (volume == 0f) mutedText else "${(volume * 100).toInt()} %"
    }

    /** What a configured shortcut does (H15); Escape is handled apart and is not configurable. */
    fun perform(action: PlayerAction) {
        when (action) {
            PlayerAction.PLAY_PAUSE -> vm.controller.togglePlay()
            PlayerAction.SEEK_BACK -> vm.controller.seekBy(-prefs.seekStep)
            PlayerAction.SEEK_FORWARD -> vm.controller.seekBy(prefs.seekStep)
            PlayerAction.VOLUME_UP -> { mutedFrom = null; setVolume(volume + 0.05f) }
            PlayerAction.VOLUME_DOWN -> { mutedFrom = null; setVolume(volume - 0.05f) }
            PlayerAction.MUTE -> {
                val restore = mutedFrom
                if (restore != null) { mutedFrom = null; setVolume(restore) } else { mutedFrom = volume.takeIf { it > 0f } ?: 0.5f; setVolume(0f) }
            }
            PlayerAction.SPEED_DOWN, PlayerAction.SPEED_UP -> {
                val steps = state.speedSteps
                val current = steps.indexOfFirst { it >= state.speed - 0.001 }.coerceAtLeast(0)
                val target = steps.getOrNull(current + if (action == PlayerAction.SPEED_UP) 1 else -1) ?: return
                vm.controller.setSpeed(target)
                feedback = "${target}×"
            }
            PlayerAction.SUBTITLES -> {
                // Off → each language in turn → off.
                val subs = state.subtitles
                if (subs.isEmpty()) return
                val index = subs.indexOfFirst { it.id.value == state.engine.selectedSubtitle }
                val next = subs.getOrNull(index + 1)
                vm.controller.selectSubtitle(next)
                feedback = subtitlesTemplate.replace("%s", next?.language?.value ?: subtitlesOffText)
            }
            PlayerAction.FULLSCREEN -> vm.setFullscreen(!fullscreen)
            PlayerAction.NEXT -> vm.next()
            PlayerAction.PREVIOUS -> vm.previous()
            PlayerAction.BOOKMARK -> { vm.addBookmark(""); feedback = bookmarkText }
            PlayerAction.HELP -> help = !help
        }
    }
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

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        BoxWithConstraints(Modifier.fillMaxSize()) { VideoBox(vm, hooks, state, scale, maxWidth, maxHeight) }
        hooks.ControlsLayer(
            Modifier.fillMaxSize()
                .focusRequester(focus).focusable()
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    touch()
                    if (event.key == Key.Escape) {
                        when {
                            help -> help = false
                            inPip -> hooks.exitPictureInPicture()
                            fullscreen -> vm.setFullscreen(false)
                            else -> onBack()
                        }
                        return@onKeyEvent true
                    }
                    val action = Keys.combo(event)?.let(prefs.shortcuts::actionFor) ?: return@onKeyEvent false
                    perform(action)
                    true
                },
        ) {
            val container = this
            val audioOnly by vm.audioOnly.collectAsStateWithLifecycle()
            if (audioOnly) AudioOnlyCover(state, Modifier.fillMaxSize())
            val cast by vm.cast.collectAsStateWithLifecycle()
            val display by vm.display.collectAsStateWithLifecycle()
            val remote = cast is CastState.Casting || cast is CastState.Connecting || display != null
            state.engine.subtitleText?.let { SubtitleText(it, Modifier.align(Alignment.BottomCenter).padding(bottom = if (controlsVisible && !inPip) 120.dp else 40.dp)) }
            if (inPip) {
                // Only reachable where the app drives PiP (desktop): click toggles playback, double click goes back.
                Box(Modifier.fillMaxSize().pointerInput(Unit) {
                    detectTapGestures(onTap = { vm.controller.togglePlay() }, onDoubleTap = { hooks.exitPictureInPicture() })
                })
                return@ControlsLayer
            }

            val hiddenPointer = hooks.hiddenPointer
            Box(
                Modifier.fillMaxSize()
                    .then(if (hiddenPointer != null && !controlsVisible && state.engine.playing) Modifier.pointerHoverIcon(hiddenPointer) else Modifier)
                    .playerGestures(prefs, scope) { action ->
                        when (action) {
                            GestureAction.ToggleControls -> if (controlsVisible) controlsVisible = false else touch()
                            GestureAction.PointerMoved -> touch()
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

            // Above the gesture layer, so its button takes taps; elsewhere taps still reach the gestures.
            if (remote) RemoteCover(vm, state, cast, display, Modifier.fillMaxSize())
            feedback?.let { Feedback(it, Modifier.align(Alignment.Center)) }

            AnimatedVisibility(
                // As a remote, the controls stay: they are what this screen is for.
                visible = controlsVisible || !state.engine.playing || remote,
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
            val belowTopBar = Modifier.statusBarsPadding().padding(top = topBarHeight(container.maxWidth) + 8.dp)
            notice?.let { Notice(it, Modifier.align(Alignment.TopCenter).then(belowTopBar)) }
            if (help) ShortcutsHelp(prefs.shortcuts, { help = false }, Modifier.align(Alignment.Center).padding(Racha.space.l))
            if (state.loopA != null) LoopBadge(state, Modifier.align(Alignment.TopStart).then(belowTopBar).padding(start = 16.dp))
        }
    }
}

@Composable
private fun VideoBox(vm: PlayerViewModel, hooks: PlayerHooks, state: PlaybackState, scale: VideoScale, maxWidth: Dp, maxHeight: Dp) {
    if (hooks.scalesVideo) {
        LaunchedEffect(scale) { hooks.setScale(vm.localEngine, scale) }
        hooks.VideoSurface(vm.localEngine, Modifier.fillMaxSize())
        return
    }
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
        hooks.VideoSurface(vm.localEngine, Modifier.requiredSize(width, height))
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

/** Audio only (H15): the course's cover and what is playing, instead of the (stopped) video. */
/** The video is on a TV or another screen (H18): this one shows where, and is the remote. */
@Composable
private fun RemoteCover(vm: PlayerViewModel, state: PlaybackState, cast: CastState, display: ExternalDisplay?, modifier: Modifier) {
    val (icon, text) = when {
        display != null -> RachaIcons.Monitor to stringResource(Res.string.cast_playing_on, display.name)
        cast is CastState.Connecting -> RachaIcons.Cast to stringResource(Res.string.cast_connecting, cast.device.name)
        cast is CastState.Casting -> RachaIcons.Tv to stringResource(Res.string.cast_playing_on, cast.device.name)
        else -> return
    }
    Column(
        modifier.background(Color.Black).padding(Racha.space.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Racha.space.m, Alignment.CenterVertically),
    ) {
        RIcon(icon, null, tint = Racha.colors.accent, size = 56.dp)
        RText(text, style = Racha.type.title, color = Color.White, textAlign = TextAlign.Center)
        state.element?.let { RText(it.displayTitle, style = Racha.type.body, color = Color.White.copy(alpha = 0.7f), textAlign = TextAlign.Center) }
        // The transport controls sit in the middle of the screen: leave them room.
        Spacer(Modifier.height(112.dp))
        RText(stringResource(Res.string.cast_remote_hint), style = Racha.type.caption, color = Color.White.copy(alpha = 0.6f), textAlign = TextAlign.Center)
        RButton(
            stringResource(Res.string.cast_stop),
            { if (display != null) vm.showOn(null) else vm.stopCasting() },
            kind = ButtonKind.Secondary, icon = RachaIcons.X,
        )
    }
}

@Composable
private fun AudioOnlyCover(state: PlaybackState, modifier: Modifier) {
    val item = state.item ?: return
    Column(
        modifier.background(Color.Black),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Racha.space.l, Alignment.CenterVertically),
    ) {
        Poster(item.posterPath, item.displayTitle, Modifier.widthIn(max = 360.dp).fillMaxWidth(0.6f))
        RText(state.element?.displayTitle ?: "", style = Racha.type.title, color = Color.White, textAlign = TextAlign.Center)
        RText(item.displayTitle, style = Racha.type.body, color = Color.White.copy(alpha = 0.7f), textAlign = TextAlign.Center)
        RText(stringResource(Res.string.audio_only_hint), style = Racha.type.caption, color = Color.White.copy(alpha = 0.6f), textAlign = TextAlign.Center)
    }
}
