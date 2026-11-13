package io.github.roberbarras.racha.desktop.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.rememberDialogState
import io.github.roberbarras.racha.application.playback.PlayerEngine
import io.github.roberbarras.racha.domain.preferences.VideoScale
import io.github.roberbarras.racha.ui.player.PlayerHooks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.awt.Canvas
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Point
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

/**
 * Linux side of the player (ADR-0011): mpv draws into a heavyweight AWT canvas by window id, and everything the
 * shared UI draws over the video lives in a transparent undecorated window owned by the main window and kept over
 * the player area. Fullscreen and picture-in-picture act on the main window; the overlay follows it.
 */
class DesktopPlayerHooks(private val hardwareDecoding: () -> Boolean) : PlayerHooks {
    private var window: ComposeWindow? = null
    private var engine: MpvEngine? = null
    private val pip = MutableStateFlow(false)
    private var beforePip: WindowSnapshot? = null
    private var aspect = 16.0 / 9

    override val inPictureInPicture: StateFlow<Boolean> = pip.asStateFlow()

    /** The engine of the open player, for the end-to-end harness. */
    internal val currentEngine: MpvEngine? get() = engine

    @Volatile private var finishSession: (suspend () -> Unit)? = null

    override fun onSessionStarted(finish: suspend () -> Unit): () -> Unit {
        finishSession = finish
        return { if (finishSession === finish) finishSession = null }
    }

    /** Final save of an open player before the app exits (closing the window does not clear view models). */
    suspend fun finishOpenSession() {
        finishSession?.also { finishSession = null }?.invoke()
    }

    /** Called once with the main window (fullscreen, PiP and the overlay's owner). */
    fun attach(window: ComposeWindow) {
        this.window = window
        X11.captureToolkitHandler()
    }

    override fun createEngine(): PlayerEngine = MpvEngine(hardwareDecoding()).also { engine = it }

    @Composable
    override fun VideoSurface(engine: PlayerEngine, modifier: Modifier) {
        val mpv = engine as MpvEngine
        val canvas = remember(engine) { Canvas().apply { background = java.awt.Color.BLACK } }
        SwingPanel(modifier = modifier, factory = { canvas })
        // mpv needs the native window id, which exists once the canvas is displayable.
        LaunchedEffect(engine) {
            while (!canvas.isDisplayable) delay(16)
            val id = X11.windowId(canvas)
            X11.trace { "canvas ${id.toString(16)} -> mpv" }
            mpv.attach(id)
        }
        DisposableEffect(engine) {
            onDispose {
                X11.trace { "surface disposed" }
                mpv.detachVideo()
                X11.trace { "video detached" }
            }
        }
    }

    override val scalesVideo: Boolean get() = true

    override fun setScale(engine: PlayerEngine, scale: VideoScale) = (engine as MpvEngine).setScale(scale)

    @Composable
    override fun ControlsLayer(modifier: Modifier, content: @Composable BoxWithConstraintsScope.() -> Unit) {
        val owner = window
        if (owner == null) {
            BoxWithConstraints(modifier, content = content)
            return
        }
        // Where the player sits inside the main window (Compose pixels), mirrored onto the screen by the overlay.
        var area by remember { mutableStateOf(Rect.Zero) }
        Box(Modifier.fillMaxSize().onGloballyPositioned { area = it.boundsInWindow() })
        val inPip by inPictureInPicture.collectAsState()
        DialogWindow(
            onCloseRequest = {},
            state = rememberDialogState(size = DpSize(1.dp, 1.dp)),
            undecorated = true,
            transparent = true,
            resizable = false,
            title = "Racha",
        ) {
            val overlay = window
            DisposableEffect(owner, area) {
                fun follow() = followArea(owner, overlay, area)
                val listener = object : ComponentAdapter() {
                    override fun componentResized(e: ComponentEvent) = follow()
                    override fun componentMoved(e: ComponentEvent) = follow()
                    override fun componentShown(e: ComponentEvent) = follow()
                }
                owner.addComponentListener(listener)
                SwingUtilities.invokeLater(::follow)
                onDispose { owner.removeComponentListener(listener) }
            }
            LaunchedEffect(inPip) { overlay.isAlwaysOnTop = inPip }
            LaunchedEffect(Unit) {
                X11.trace { "overlay ${runCatching { X11.windowId(overlay).toString(16) }.getOrDefault("?")}" }
                // Focusing a window that is not mapped yet is an X error (BadMatch): wait until it shows.
                while (!overlay.isShowing) delay(16)
                overlay.toFront()
                overlay.requestFocus()
            }
            BoxWithConstraints(modifier, content = content)
        }
    }

    override val hiddenPointer: PointerIcon by lazy {
        val blank = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
        PointerIcon(Toolkit.getDefaultToolkit().createCustomCursor(blank, Point(0, 0), "racha-hidden"))
    }

    override fun setFullscreen(fullscreen: Boolean) {
        val window = window ?: return
        if (pip.value) exitPictureInPicture()
        window.placement = if (fullscreen) WindowPlacement.Fullscreen else WindowPlacement.Floating
    }

    /** A small always-on-top window in the bottom-right corner of the current screen; double click restores. */
    override fun enterPictureInPicture(videoWidth: Int, videoHeight: Int): Boolean {
        val window = window ?: return false
        if (pip.value) return true
        if (videoWidth > 0 && videoHeight > 0) aspect = videoWidth.toDouble() / videoHeight
        beforePip = WindowSnapshot(window.bounds, window.placement, window.minimumSize)
        window.placement = WindowPlacement.Floating
        val screen = window.graphicsConfiguration
        val insets = Toolkit.getDefaultToolkit().getScreenInsets(screen)
        val usable = screen.bounds.let { Rectangle(it.x + insets.left, it.y + insets.top, it.width - insets.left - insets.right, it.height - insets.top - insets.bottom) }
        val width = PIP_WIDTH
        val height = (width / aspect).toInt().coerceIn(PIP_WIDTH / 3, PIP_WIDTH)
        window.minimumSize = Dimension(160, 90)
        window.bounds = Rectangle(usable.x + usable.width - width - PIP_MARGIN, usable.y + usable.height - height - PIP_MARGIN, width, height)
        window.isAlwaysOnTop = true
        pip.value = true
        return true
    }

    override fun exitPictureInPicture() {
        val window = window ?: return
        val saved = beforePip ?: return
        beforePip = null
        window.isAlwaysOnTop = false
        window.minimumSize = saved.minimumSize
        window.bounds = saved.bounds
        window.placement = saved.placement
        pip.value = false
    }

    /** mpv inhibits the screensaver itself while it plays video; nothing else to do here. */
    override fun onPlayingChanged(playing: Boolean, videoWidth: Int, videoHeight: Int) {
        if (videoWidth > 0 && videoHeight > 0) aspect = videoWidth.toDouble() / videoHeight
    }

    override fun onClosed() {
        if (pip.value) exitPictureInPicture()
    }

    /** "Brightness" of the video picture (the screen's backlight is the desktop's business). */
    override fun brightness(): Float = engine?.brightness() ?: 0.5f

    override fun setBrightness(value: Float) {
        engine?.setBrightness(value)
    }

    override fun volume(): Float = engine?.volume() ?: 1f

    override fun setVolume(value: Float) {
        engine?.setVolume(value)
    }

    override suspend fun screenshot(engine: PlayerEngine, name: String): String? = withContext(Dispatchers.IO) {
        val dir = picturesDirectory().resolve("Racha")
        Files.createDirectories(dir)
        val file = dir.resolve("${name.replace(Regex("[^\\p{L}\\p{N} ._-]"), "_").take(120)}.png")
        if (!(engine as MpvEngine).screenshot(file)) return@withContext null
        // screenshot-to-file is asynchronous in mpv: wait briefly for the file to appear.
        repeat(40) { if (Files.size(file.takeIf(Files::exists) ?: return@repeat) > 0) return@withContext file.toString(); delay(50) }
        file.takeIf(Files::exists)?.toString()
    }

    override fun language(): String = Locale.getDefault().language.ifEmpty { "en" }

    private data class WindowSnapshot(val bounds: Rectangle, val placement: WindowPlacement, val minimumSize: Dimension)

    private companion object {
        const val PIP_WIDTH = 480
        const val PIP_MARGIN = 24

        /** Places [overlay] exactly over [area] (Compose pixels of [owner]'s content) in screen coordinates. */
        fun followArea(owner: ComposeWindow, overlay: Window, area: Rect) {
            if (!owner.isShowing) return
            val scale = owner.graphicsConfiguration.defaultTransform.scaleX
            val origin = owner.contentPane.locationOnScreen
            val bounds = if (area.width > 0f) {
                Rectangle(
                    origin.x + (area.left / scale).toInt(), origin.y + (area.top / scale).toInt(),
                    (area.width / scale).toInt(), (area.height / scale).toInt(),
                )
            } else {
                Rectangle(origin.x, origin.y, owner.contentPane.width, owner.contentPane.height)
            }
            if (overlay.bounds != bounds) overlay.bounds = bounds
        }

        /** XDG pictures folder (localised, e.g. ~/Imágenes), ~/Pictures if xdg-user-dir is missing. */
        fun picturesDirectory(): Path {
            val xdg = runCatching {
                val process = ProcessBuilder("xdg-user-dir", "PICTURES").redirectError(ProcessBuilder.Redirect.DISCARD).start()
                process.waitFor(2, TimeUnit.SECONDS)
                process.inputStream.bufferedReader().readText().trim()
            }.getOrNull()
            val home = System.getProperty("user.home")
            return Path.of(xdg?.takeIf { it.isNotEmpty() && it != home } ?: "$home/Pictures")
        }
    }
}
