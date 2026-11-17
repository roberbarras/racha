package io.github.roberbarras.racha.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.roberbarras.racha.application.playback.PlaybackState
import io.github.roberbarras.racha.domain.library.ContentId
import io.github.roberbarras.racha.domain.playback.StartMode
import io.github.roberbarras.racha.domain.playback.Shortcuts
import io.github.roberbarras.racha.domain.preferences.Settings
import io.github.roberbarras.racha.domain.preferences.VideoScale
import io.github.roberbarras.racha.ui.app.RachaDependencies
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import io.github.roberbarras.racha.application.cast.CastState
import io.github.roberbarras.racha.application.cast.CastingEngine
import io.github.roberbarras.racha.application.playback.PlayerEngine
import io.github.roberbarras.racha.domain.cast.CastDevice
import io.github.roberbarras.racha.ui.resources.Res
import io.github.roberbarras.racha.ui.resources.cast_failed_connect
import io.github.roberbarras.racha.ui.resources.cast_failed_load
import io.github.roberbarras.racha.ui.resources.cast_lost
import org.jetbrains.compose.resources.getString
import kotlinx.coroutines.launch

/** Gesture and control settings read once per player (spec §4.2 "todos configurables"). */
data class PlayerPreferences(
    val seekStep: Int = Settings.SeekStepSeconds.default,
    val tapEdges: Boolean = true,
    val doubleTap: Boolean = true,
    val swipeSeek: Boolean = true,
    val swipeBrightness: Boolean = true,
    val swipeVolume: Boolean = true,
    val pinchZoom: Boolean = true,
    val longPressSpeed: Boolean = true,
    val longPressRate: Double = Settings.LongPressSpeed.default,
    /** Keyboard shortcuts of this device (H15). */
    val shortcuts: Shortcuts = Shortcuts.DEFAULT,
)

enum class PlayerPanel { None, Queue, Speed, Subtitles, Audio, Bookmarks, Settings, Summary, Cast }

class PlayerViewModel(
    internal val deps: RachaDependencies,
    private val hooks: PlayerHooks,
    element: ContentId,
    mode: StartMode,
) : ViewModel() {
    /** This device's engine: the platform's hooks (surface, screenshots, audio only) always get this one. */
    val localEngine = hooks.createEngine()
    /** With "send to" (H18), the engine can move to a TV; the controller drives whichever plays. */
    private val casting: CastingEngine? = deps.cast?.let { CastingEngine(localEngine, it.connector, it.publisher(), deps.appScope) }
    val engine: PlayerEngine = casting ?: localEngine
    val controller = deps.playbackController(engine, viewModelScope)
    val cast: StateFlow<CastState> = casting?.cast ?: MutableStateFlow(CastState.Local)
    private val mutableDisplay = MutableStateFlow<ExternalDisplay?>(null)
    /** The external screen showing the video, if any (H18). */
    val display: StateFlow<ExternalDisplay?> = mutableDisplay.asStateFlow()
    val externalDisplays: StateFlow<List<ExternalDisplay>> = hooks.externalDisplays
    val castOffered: Boolean get() = casting != null

    fun castTo(device: CastDevice) {
        showOn(null)
        casting?.castTo(device)
        controller.usedFeature("cast_${device.kind.name.lowercase()}")
    }

    fun stopCasting() = casting?.stopCasting()

    fun showOn(target: ExternalDisplay?) {
        if (mutableDisplay.value == target) return
        if (target != null) casting?.stopCasting()
        hooks.showOnDisplay(localEngine, target?.id)
        mutableDisplay.value = target
        if (target != null) controller.usedFeature("external_display")
    }

    fun requestLocalNetwork() = hooks.requestLocalNetwork()
    val state: StateFlow<PlaybackState> = controller.state

    private val mutableFullscreen = MutableStateFlow(false)
    /** Survives autoplay-next: the next element starts in the same mode (user request). */
    val fullscreen: StateFlow<Boolean> = mutableFullscreen.asStateFlow()

    val panel = MutableStateFlow(PlayerPanel.None)
    val scale = MutableStateFlow(VideoScale.FIT)
    val preferences = MutableStateFlow(PlayerPreferences())
    val notice = MutableStateFlow<String?>(null)

    private val endSession = hooks.onSessionStarted { controller.close() }

    init {
        viewModelScope.launch {
            val p = deps.preferences
            preferences.value = PlayerPreferences(
                seekStep = p.get(Settings.SeekStepSeconds),
                tapEdges = p.get(Settings.GestureTapEdges),
                doubleTap = p.get(Settings.GestureDoubleTap),
                swipeSeek = p.get(Settings.GestureSwipeSeek),
                swipeBrightness = p.get(Settings.GestureSwipeBrightness),
                swipeVolume = p.get(Settings.GestureSwipeVolume),
                pinchZoom = p.get(Settings.GesturePinchZoom),
                longPressSpeed = p.get(Settings.GestureLongPressSpeed),
                longPressRate = p.get(Settings.LongPressSpeed),
                shortcuts = Shortcuts.decode(p.get(Settings.PlayerShortcuts)),
            )
            scale.value = p.get(Settings.Zoom)
            if (p.get(Settings.ShowSidebar)) panel.value = PlayerPanel.Queue
            controller.open(element, mode)
        }
        viewModelScope.launch {
            // While a TV plays, this screen may turn off (it is only the remote).
            combine(state, cast) { s, c -> Triple(s.engine.playing && c !is CastState.Casting, s.engine.videoWidth, s.engine.videoHeight) }
                .distinctUntilChanged()
                .collect { (playing, w, h) -> hooks.onPlayingChanged(playing, w, h) }
        }
        viewModelScope.launch {
            // Cable unplugged: the video comes back here.
            hooks.externalDisplays.collect { list -> mutableDisplay.value?.let { shown -> if (list.none { it.id == shown.id }) showOn(null) } }
        }
        viewModelScope.launch {
            cast.collect { c ->
                notice.value = when (c) {
                    is CastState.Failed -> getString(
                        when (c.reason) {
                            "load" -> Res.string.cast_failed_load
                            "lost" -> Res.string.cast_lost
                            else -> Res.string.cast_failed_connect
                        },
                        c.device.name,
                    )
                    else -> notice.value
                }
            }
        }
    }

    private val mutableAudioOnly = MutableStateFlow(false)
    /** Audio only (H15): survives autoplay-next like full screen; the screen may turn off and playback goes on. */
    val audioOnly: StateFlow<Boolean> = mutableAudioOnly.asStateFlow()

    fun setAudioOnly(enabled: Boolean) {
        if (mutableAudioOnly.value == enabled) return
        mutableAudioOnly.value = enabled
        engine.setVideoEnabled(!enabled)
        hooks.setAudioOnly(localEngine, enabled)
        if (enabled) controller.usedFeature("audio_only")
    }

    fun setFullscreen(enabled: Boolean) {
        mutableFullscreen.value = enabled
        hooks.setFullscreen(enabled)
        if (enabled) controller.usedFeature("fullscreen")
    }

    fun togglePanel(target: PlayerPanel) {
        panel.value = if (panel.value == target) PlayerPanel.None else target
    }

    fun answerResume(resume: Boolean) = viewModelScope.launch { controller.answerResume(resume) }
    fun next() = viewModelScope.launch { controller.next() }
    fun previous() = viewModelScope.launch { controller.previous() }
    fun jumpTo(id: ContentId) = viewModelScope.launch { controller.jumpTo(id) }
    fun addBookmark(note: String) = viewModelScope.launch { controller.addBookmark(note) }
    fun deleteBookmark(id: String) = viewModelScope.launch { controller.deleteBookmark(id) }
    fun setSkips(intro: Int?, outro: Int?) = viewModelScope.launch { controller.setSkips(intro, outro) }
    fun onBackground() = viewModelScope.launch { controller.onBackground() }

    fun screenshot(savedMessage: suspend (String) -> String) = viewModelScope.launch {
        val element = state.value.element ?: return@launch
        val name = "racha-${element.id.value.take(8)}-${engine.positionMs() / 1000}"
        hooks.screenshot(localEngine, name)?.let { notice.value = savedMessage(it) }
        controller.usedFeature("screenshot")
    }

    fun enterPictureInPicture() {
        val e = state.value.engine
        hooks.enterPictureInPicture(e.videoWidth, e.videoHeight)
        controller.usedFeature("pip")
    }

    override fun onCleared() {
        if (mutableAudioOnly.value) hooks.setAudioOnly(localEngine, false)
        if (mutableDisplay.value != null) hooks.showOnDisplay(localEngine, null)
        hooks.setFullscreen(false)
        hooks.onPlayingChanged(false, 0, 0)
        hooks.onClosed()
        endSession()
        // The view model scope is gone: finish the final save, history entry and release on the app scope.
        deps.appScope.launch { controller.close() }
    }
}
