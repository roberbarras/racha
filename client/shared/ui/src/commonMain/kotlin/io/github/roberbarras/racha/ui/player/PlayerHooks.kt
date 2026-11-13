package io.github.roberbarras.racha.ui.player

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import io.github.roberbarras.racha.application.playback.PlayerEngine
import io.github.roberbarras.racha.domain.preferences.VideoScale
import kotlinx.coroutines.flow.StateFlow

/** What the shared player UI needs from the platform (Media3 on Android, libmpv on desktop). */
interface PlayerHooks {
    fun createEngine(): PlayerEngine

    /** The video output of [engine], sized by the caller (or filling it when [scalesVideo]). */
    @Composable
    fun VideoSurface(engine: PlayerEngine, modifier: Modifier)

    /** True when the engine scales the picture itself (desktop: mpv in a native window that Compose cannot clip). */
    val scalesVideo: Boolean get() = false

    fun setScale(engine: PlayerEngine, scale: VideoScale) = Unit

    /**
     * Everything drawn over the video (subtitles, gestures, controls, panels). Inline on Android; on desktop a
     * transparent window kept over the player, because mpv's native output cannot be drawn over (ADR-0011).
     */
    @Composable
    fun ControlsLayer(modifier: Modifier, content: @Composable BoxWithConstraintsScope.() -> Unit) {
        BoxWithConstraints(modifier, content = content)
    }

    /** Pointer shown while the controls are hidden; null keeps the default (touch platforms). */
    val hiddenPointer: PointerIcon? get() = null

    /** Immersive/full-window playback (spec §4.2); kept across autoplay-next by the caller. */
    fun setFullscreen(fullscreen: Boolean)

    /** Picture-in-picture (spec §4.2); false if the system refuses it. */
    fun enterPictureInPicture(videoWidth: Int, videoHeight: Int): Boolean

    /** Back from picture-in-picture where the app drives it (desktop); Android leaves it to the system. */
    fun exitPictureInPicture() = Unit

    val inPictureInPicture: StateFlow<Boolean>

    /** Tells the platform whether playback is running (automatic PiP on leaving the app, screen kept on). */
    fun onPlayingChanged(playing: Boolean, videoWidth: Int, videoHeight: Int)

    /** Brightness 0..1 for the vertical swipe on the left (window brightness on Android). */
    fun brightness(): Float

    fun setBrightness(value: Float)

    /** Volume 0..1 for the vertical swipe on the right. */
    fun volume(): Float

    fun setVolume(value: Float)

    /** Saves the current frame (spec §4.2 "captura"); returns where it was saved, or null. */
    suspend fun screenshot(engine: PlayerEngine, name: String): String?

    /** Two-letter UI language, used to pick subtitles. */
    fun language(): String

    /** The player closed: undo window changes it made (brightness override…). */
    fun onClosed() = Unit

    /**
     * A player opened with its "finish" (final save, history, release); the returned function unregisters it. Desktop
     * calls the latest one when the window is closed mid-playback, where view models are not cleared before the
     * process exits. Unregistering only removes that same player (an old one may be cleared after a new one opened).
     */
    fun onSessionStarted(finish: suspend () -> Unit): () -> Unit = {}
}
