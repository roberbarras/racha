package io.github.roberbarras.racha.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.roberbarras.racha.application.playback.PlayerEngine
import kotlinx.coroutines.flow.StateFlow

/** What the shared player UI needs from the platform (Media3 on Android, libmpv on desktop). */
interface PlayerHooks {
    fun createEngine(): PlayerEngine

    /** The video output of [engine], sized by the caller. */
    @Composable
    fun VideoSurface(engine: PlayerEngine, modifier: Modifier)

    /** Immersive/full-window playback (spec §4.2); kept across autoplay-next by the caller. */
    fun setFullscreen(fullscreen: Boolean)

    /** Picture-in-picture (spec §4.2); false if the system refuses it. */
    fun enterPictureInPicture(videoWidth: Int, videoHeight: Int): Boolean

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
}
