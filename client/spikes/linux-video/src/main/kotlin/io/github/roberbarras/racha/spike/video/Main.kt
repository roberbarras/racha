package io.github.roberbarras.racha.spike.video

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberDialogState
import androidx.compose.ui.window.rememberWindowState
import java.awt.Canvas
import java.awt.Component
import java.awt.Rectangle
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.SwingUtilities
import kotlin.system.exitProcess
import kotlinx.coroutines.delay

/**
 * Usage:
 * - `embed <video> [measureSeconds] [width] [height] [hwdec] [vo]`: mpv draws natively into a child window of our
 *   Compose window (zero-copy decode + present); the player chrome lives in a transparent window stacked on top.
 * - `probe <video> <outDir>`: headless metadata + thumbnail.
 * Prints one `RESULT {...}` JSON line and exits.
 */
fun main(args: Array<String>) {
    if (args[0] == "probe") return runProbe(args[1], args[2])
    val config = Config(
        mode = args[0],
        video = args[1],
        measureSeconds = args.getOrNull(2)?.toInt() ?: 30,
        width = args.getOrNull(3)?.toInt() ?: 1280,
        height = args.getOrNull(4)?.toInt() ?: 720,
        hwdec = args.getOrNull(5) ?: "auto-safe",
        vo = args.getOrNull(6) ?: "gpu-next",
    )
    require(config.mode == "embed") { "unknown mode ${config.mode}" }
    runEmbedded(config)
}

data class Config(
    val mode: String,
    val video: String,
    val measureSeconds: Int,
    val width: Int,
    val height: Int,
    val hwdec: String,
    val vo: String,
)

private const val WARMUP_SECONDS = 8

private fun runEmbedded(config: Config) = application {
    val windowState = rememberWindowState(
        size = DpSize(config.width.dp, config.height.dp),
        position = WindowPosition(Alignment.Center),
    )
    Window(onCloseRequest = ::exitApplication, state = windowState, title = "racha-spike-embed") {
        val videoSurface = remember { Canvas().apply { background = java.awt.Color.BLACK } }
        var mpv by remember { mutableStateOf<Mpv?>(null) }
        var positionLabel by remember { mutableStateOf("--:--") }

        Box(Modifier.fillMaxSize().background(Color.Black)) {
            SwingPanel(modifier = Modifier.fillMaxSize(), factory = { videoSurface })
        }

        // mpv needs the native window id, which exists only once the canvas is displayable.
        LaunchedEffect(Unit) {
            while (!videoSurface.isDisplayable) delay(20)
            mpv = Mpv().apply {
                setOption("wid", X11.windowId(videoSurface).toString())
                setOption("vo", config.vo)
                setOption("hwdec", config.hwdec)
                setOption("mute", "yes")
                setOption("keep-open", "yes")
                setOption("terminal", "no")
                setOption("input-default-bindings", "no")
                setOption("input-vo-keyboard", "no")
                setOption("input-cursor", "no") // pointer events go to our chrome window, not to mpv
                initialize()
                command("loadfile", config.video)
            }
        }

        LaunchedEffect(mpv) {
            val player = mpv ?: return@LaunchedEffect
            val measurement = Measurement(config, player)
            var elapsed = 0
            while (true) {
                delay(1000)
                elapsed++
                positionLabel = player.getProperty("time-pos")?.toDoubleOrNull()?.let(::clock) ?: "--:--"
                if (elapsed == WARMUP_SECONDS) measurement.start()
                if (elapsed == WARMUP_SECONDS + config.measureSeconds) {
                    println("RESULT " + measurement.finish())
                    player.close()
                    exitProcess(0)
                }
            }
        }

        ChromeWindow(anchor = videoSurface, positionLabel = positionLabel)
    }
}

/**
 * Transparent, undecorated window owned by the player window and kept exactly over the video surface, so the
 * Compose chrome (controls, sidebar, gestures) is composited above mpv's native output by the window manager.
 */
@Composable
private fun ChromeWindow(anchor: Component, positionLabel: String) {
    DialogWindow(
        onCloseRequest = {},
        state = rememberDialogState(size = DpSize(1.dp, 1.dp)),
        undecorated = true,
        transparent = true,
        resizable = false,
        title = "racha-spike-chrome",
    ) {
        DisposableEffect(anchor) {
            fun follow() {
                if (!anchor.isShowing) return
                val origin = anchor.locationOnScreen
                window.bounds = Rectangle(origin.x, origin.y, anchor.width, anchor.height)
                System.err.println("chrome bounds -> ${window.bounds} visible=${window.isVisible}")
            }
            val listener = object : ComponentAdapter() {
                override fun componentResized(e: ComponentEvent) = follow()
                override fun componentMoved(e: ComponentEvent) = follow()
                override fun componentShown(e: ComponentEvent) = follow()
            }
            val topLevel = SwingUtilities.getWindowAncestor(anchor)
            anchor.addComponentListener(listener)
            topLevel?.addComponentListener(listener)
            val hierarchy = java.awt.event.HierarchyListener { e ->
                if (e.changeFlags and java.awt.event.HierarchyEvent.SHOWING_CHANGED.toLong() != 0L) follow()
            }
            anchor.addHierarchyListener(hierarchy)
            SwingUtilities.invokeLater(::follow)
            onDispose {
                anchor.removeHierarchyListener(hierarchy)
                anchor.removeComponentListener(listener)
                topLevel?.removeComponentListener(listener)
            }
        }
        PlayerChrome(positionLabel)
    }
}

/** Stand-in for the real player chrome: translucent controls and a sidebar drawn over the video. */
@Composable
private fun PlayerChrome(positionLabel: String) {
    val text = TextStyle(color = Color(0xFFF2EFE9), fontSize = 14.sp)
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(260.dp).background(Color(0xB314161A)).padding(12.dp),
        ) {
            repeat(8) { BasicText("${it + 1}. Lesson ${it + 1}", Modifier.padding(vertical = 6.dp), style = text) }
        }
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(56.dp).background(Color(0xB314161A)).padding(16.dp)) {
            BasicText("▶  $positionLabel", style = text)
        }
    }
}

private fun clock(seconds: Double): String = "%02d:%02d".format(seconds.toInt() / 60, seconds.toInt() % 60)
