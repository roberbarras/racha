package io.github.roberbarras.racha.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import io.github.roberbarras.racha.application.cast.CastServices
import io.github.roberbarras.racha.application.cast.MediaPublisher
import io.github.roberbarras.racha.application.cast.RemoteMedia
import io.github.roberbarras.racha.application.cast.RemotePlayer
import io.github.roberbarras.racha.application.cast.RemoteStatus
import io.github.roberbarras.racha.application.playback.EngineSnapshot
import io.github.roberbarras.racha.application.playback.EngineState
import io.github.roberbarras.racha.application.playback.MediaSource
import io.github.roberbarras.racha.application.playback.PlayerEngine
import io.github.roberbarras.racha.domain.cast.CastDevice
import io.github.roberbarras.racha.domain.cast.CastKind
import io.github.roberbarras.racha.domain.playback.StartMode
import io.github.roberbarras.racha.ui.design.Racha
import io.github.roberbarras.racha.ui.design.RachaTheme
import io.github.roberbarras.racha.ui.player.ExternalDisplay
import io.github.roberbarras.racha.ui.player.PlayerHooks
import io.github.roberbarras.racha.ui.player.PlayerScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import java.util.Collections
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** "Send to" in the player (H18): a TV and a connected screen take the video; this device stays as the remote. */
@OptIn(ExperimentalTestApi::class)
class CastUiTest {
    private val originalLocale = Locale.getDefault()
    private val out = java.io.File(System.getProperty("racha.screenshots") ?: "build/screenshots").apply { mkdirs() }

    @AfterTest
    fun restoreLocale() = Locale.setDefault(originalLocale)

    private class FakeEngine : PlayerEngine {
        override val snapshot = MutableStateFlow(EngineSnapshot())
        var position = 0L
        override fun positionMs() = position
        override fun load(source: MediaSource, startMs: Long, playWhenReady: Boolean) {
            position = startMs
            snapshot.value = EngineSnapshot(EngineState.READY, playWhenReady, durationMs = 2_710_000, videoWidth = 1920, videoHeight = 1080)
        }
        override fun play() { snapshot.value = snapshot.value.copy(playing = true) }
        override fun pause() { snapshot.value = snapshot.value.copy(playing = false) }
        override fun seekTo(positionMs: Long) { position = positionMs }
        override fun setSpeed(speed: Float) {}
        override fun selectSubtitle(id: String?) {}
        override fun selectAudio(id: String?) {}
        override fun release() {}
    }

    private class FakeHooks : PlayerHooks {
        val engine = FakeEngine()
        val shownOn: MutableList<Int?> = Collections.synchronizedList(mutableListOf())
        override val externalDisplays = MutableStateFlow(listOf(ExternalDisplay(2, "HDMI Screen")))
        override fun showOnDisplay(engine: PlayerEngine, displayId: Int?) { shownOn += displayId }
        override fun createEngine(): PlayerEngine = engine
        @Composable override fun VideoSurface(engine: PlayerEngine, modifier: Modifier) = Box(modifier.background(androidx.compose.ui.graphics.Color.DarkGray))
        override fun setFullscreen(fullscreen: Boolean) {}
        override fun enterPictureInPicture(videoWidth: Int, videoHeight: Int) = false
        override val inPictureInPicture: StateFlow<Boolean> = MutableStateFlow(false)
        override fun onPlayingChanged(playing: Boolean, videoWidth: Int, videoHeight: Int) {}
        override fun brightness() = 0.5f
        override fun setBrightness(value: Float) {}
        override fun volume() = 0.5f
        override fun setVolume(value: Float) {}
        override suspend fun screenshot(engine: PlayerEngine, name: String): String? = null
        override fun language() = "en"
    }

    private class FakeTv : RemotePlayer {
        val loads: MutableList<RemoteMedia> = Collections.synchronizedList(mutableListOf())
        @Volatile var stopped = false
        override val supportsSpeed = true
        override suspend fun load(media: RemoteMedia, play: Boolean) { loads += media }
        override suspend fun play() {}
        override suspend fun pause() {}
        override suspend fun seek(positionMs: Long) {}
        override suspend fun setSpeed(speed: Float) {}
        override suspend fun status() = RemoteStatus(EngineState.READY, true, loads.lastOrNull()?.startMs ?: 0, 2_710_000)
        override suspend fun stop() { stopped = true }
        override fun close() {}
    }

    @Test
    fun aLessonGoesToTheTvAndToAConnectedScreenAndComesBack() = runDesktopComposeUiTest(1280, 800) {
        Locale.setDefault(Locale.ENGLISH)
        val hooks = FakeHooks()
        val tv = FakeTv()
        val salon = CastDevice("cc-1", "Living room", CastKind.CHROMECAST, "192.168.1.20:8009", "Chromecast")
        val cast = CastServices(
            discovery = { flowOf(listOf(salon, CastDevice("uuid:tv", "[TV] Bedroom", CastKind.DLNA, "http://192.168.1.30:9197/d.xml"))) },
            connector = { tv },
            publisher = {
                object : MediaPublisher {
                    override suspend fun publishFile(uri: String, contentType: String, forHost: String) = "http://192.168.1.5:4000/t/v.mp4"
                    override suspend fun publishSubtitle(uri: String, forHost: String) = "http://192.168.1.5:4000/t/v.vtt"
                    override fun stop() {}
                }
            },
        )
        val deps = DemoLibrary.dependencies(player = hooks, cast = cast)
        val lesson = DemoLibrary.awsIamLesson
        setContent {
            val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                RachaTheme(dark = true) {
                    Box(Modifier.fillMaxSize().background(Racha.colors.background)) {
                        PlayerScreen(deps, hooks, lesson.id, StartMode.BEGINNING, onBack = {}, onOpenResource = {})
                    }
                }
            }
        }
        waitUntil(timeoutMillis = 30_000) { hooks.engine.snapshot.value.state == EngineState.READY }
        hooks.engine.position = 754_000

        onAllNodesWithContentDescription("Send to")[0].performClick()
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Living room").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("[TV] Bedroom").assertExists()
        onNodeWithText("HDMI Screen").assertExists()
        javax.imageio.ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", java.io.File(out, "desktop-cast-panel-en.png"))

        onNodeWithText("Living room").performClick()
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Playing on Living room").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(754_000, tv.loads.single().startMs, "the TV starts where this device was")
        assertEquals("http://192.168.1.5:4000/t/v.mp4", tv.loads.single().url)
        assertTrue(!hooks.engine.snapshot.value.playing, "this device stops playing")
        javax.imageio.ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", java.io.File(out, "desktop-cast-remote-en.png"))

        onNodeWithText("Back to this device").performClick()
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Playing on Living room").fetchSemanticsNodes().isEmpty() && tv.stopped }

        // A screen plugged into the device (the panel is still open).
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("HDMI Screen").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("HDMI Screen").performClick()
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Playing on HDMI Screen").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf<Int?>(2), hooks.shownOn.toList())
        // Unplugged: back here by itself.
        hooks.externalDisplays.value = emptyList()
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Playing on HDMI Screen").fetchSemanticsNodes().isEmpty() }
        assertEquals(listOf(2, null), hooks.shownOn.toList())
    }
}
