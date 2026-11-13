package io.github.roberbarras.racha.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.roberbarras.racha.application.playback.PlaybackState
import io.github.roberbarras.racha.domain.library.ContentId
import io.github.roberbarras.racha.domain.playback.StartMode
import io.github.roberbarras.racha.domain.preferences.Settings
import io.github.roberbarras.racha.domain.preferences.VideoScale
import io.github.roberbarras.racha.ui.app.RachaDependencies
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
)

enum class PlayerPanel { None, Queue, Speed, Subtitles, Audio, Bookmarks, Settings }

class PlayerViewModel(
    private val deps: RachaDependencies,
    private val hooks: PlayerHooks,
    element: ContentId,
    mode: StartMode,
) : ViewModel() {
    val engine = hooks.createEngine()
    val controller = deps.playbackController(engine, viewModelScope)
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
            )
            scale.value = p.get(Settings.Zoom)
            if (p.get(Settings.ShowSidebar)) panel.value = PlayerPanel.Queue
            controller.open(element, mode)
        }
        viewModelScope.launch {
            state.map { Triple(it.engine.playing, it.engine.videoWidth, it.engine.videoHeight) }.distinctUntilChanged()
                .collect { (playing, w, h) -> hooks.onPlayingChanged(playing, w, h) }
        }
    }

    fun setFullscreen(enabled: Boolean) {
        mutableFullscreen.value = enabled
        hooks.setFullscreen(enabled)
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
        hooks.screenshot(engine, name)?.let { notice.value = savedMessage(it) }
    }

    fun enterPictureInPicture() {
        val e = state.value.engine
        hooks.enterPictureInPicture(e.videoWidth, e.videoHeight)
    }

    override fun onCleared() {
        hooks.setFullscreen(false)
        hooks.onPlayingChanged(false, 0, 0)
        hooks.onClosed()
        endSession()
        // The view model scope is gone: finish the final save, history entry and release on the app scope.
        deps.appScope.launch { controller.close() }
    }
}
