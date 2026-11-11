package io.github.roberbarras.racha.spike.video

import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.JAVA_INT
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.swing.SwingUtilities
import kotlin.system.exitProcess
import kotlinx.coroutines.delay
import org.jetbrains.skia.BackendTexture
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Pixmap
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SurfaceOrigin

/**
 * Usage: `<mode> <video> [measureSeconds] [width] [height] [hwdec]`, mode ∈ gl | sw | window | idle.
 * Prints one `RESULT {...}` JSON line and exits.
 */
fun main(args: Array<String>) {
    if (args[0] == "probe") return runProbe(args[1], args[2])
    val config = Config(
        mode = args[0],
        video = args.getOrElse(1) { "" },
        measureSeconds = args.getOrNull(2)?.toInt() ?: 60,
        width = args.getOrNull(3)?.toInt() ?: 1280,
        height = args.getOrNull(4)?.toInt() ?: 720,
        hwdec = args.getOrNull(5) ?: "auto-safe",
    )
    val stats = Stats()
    if (config.mode == "window") runOwnWindow(config, stats) else runCompose(config, stats)
}

data class Config(val mode: String, val video: String, val measureSeconds: Int, val width: Int, val height: Int, val hwdec: String)

class Stats {
    val videoFrames = AtomicLong()
    val composeDraws = AtomicLong()
}

private const val WARMUP_SECONDS = 8

private fun newMpv(config: Config, vo: String): Mpv = Mpv().apply {
    setOption("vo", vo)
    setOption("hwdec", config.hwdec)
    setOption("mute", "yes")
    setOption("keep-open", "yes")
    setOption("terminal", "no")
    if (vo != "libmpv") {
        setOption("geometry", "${config.width}x${config.height}")
        setOption("title", "racha-spike-${config.mode}")
    }
    initialize()
}

/** Reference: mpv presents through its own native window (no Compose involved). */
private fun runOwnWindow(config: Config, stats: Stats) {
    val mpv = newMpv(config, "gpu-next")
    mpv.command("loadfile", config.video)
    val measurement = Measurement(config, stats, mpv)
    Thread.sleep(WARMUP_SECONDS * 1000L)
    measurement.start()
    Thread.sleep(config.measureSeconds * 1000L)
    println("RESULT " + measurement.finish())
    mpv.close()
    exitProcess(0)
}

private fun runCompose(config: Config, stats: Stats) = application {
    val mpv = remember {
        when (config.mode) {
            "gl" -> newMpv(config, "libmpv")
            "sw" -> newMpv(config, "libmpv").also { it.createSoftwareRenderContext() }
            else -> null
        }
    }
    val windowState = rememberWindowState(
        size = DpSize(config.width.dp, config.height.dp),
        position = WindowPosition(Alignment.Center),
    )
    Window(onCloseRequest = ::exitApplication, state = windowState, title = "racha-spike-${config.mode}") {
        var positionLabel by remember { mutableStateOf("--:--") }
        LaunchedEffect(Unit) {
            val measurement = Measurement(config, stats, mpv)
            var elapsed = 0
            while (true) {
                delay(1000)
                elapsed++
                positionLabel = mpv?.getProperty("time-pos")?.toDoubleOrNull()?.let(::clock) ?: "--:--"
                if (elapsed == WARMUP_SECONDS) measurement.start()
                if (elapsed == WARMUP_SECONDS + config.measureSeconds) {
                    println("RESULT " + measurement.finish() + " renderApi=" + SkikoInternals.renderApi(window))
                    exitProcess(0)
                }
            }
        }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            when (config.mode) {
                "gl" -> GlVideo(mpv!!, config.video, window, stats, Modifier.fillMaxSize())
                "sw" -> SoftwareVideo(mpv!!, config.video, stats, Modifier.fillMaxSize())
            }
            PlayerChromeOverlay(positionLabel)
        }
    }
}

/** Stand-in for the real player chrome: translucent controls and a sidebar drawn over the video. */
@Composable
private fun PlayerChromeOverlay(positionLabel: String) {
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

/** Zero-copy path: mpv renders into a GL texture that Skia adopts and composites with the Compose UI. */
@Composable
private fun GlVideo(mpv: Mpv, video: String, window: ComposeWindow, stats: Stats, modifier: Modifier) {
    var frameTick by remember { mutableLongStateOf(0L) }
    val renderer = remember {
        GlRenderer(mpv, video, stats) { SwingUtilities.invokeLater { frameTick++ } }.also { renderer ->
            check(SkikoInternals.installPreDrawHook(window, renderer::renderFrame)) {
                "Skiko internals changed:\n" + SkikoInternals.describe(window)
            }
        }
    }
    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION")
        frameTick // reading the state makes mpv frame updates invalidate this draw
        stats.composeDraws.incrementAndGet()
        renderer.record(this)
    }
}

/**
 * Two phases, both on the AWT event thread:
 * - [record] runs while Compose records its picture (no GL context): it only references the texture-backed image.
 * - [renderFrame] runs in Skiko's GL draw pass, before the picture is replayed: mpv renders into the texture.
 */
private class GlRenderer(
    private val mpv: Mpv,
    private val video: String,
    private val stats: Stats,
    private val requestRedraw: () -> Unit,
) {
    private var gl: Gl? = null
    private var target: Gl.TextureTarget? = null
    @Volatile private var image: Image? = null
    @Volatile private var requestedWidth = 0
    @Volatile private var requestedHeight = 0
    private val redrawPending = AtomicBoolean(false)
    private val fboParams: MemorySegment = Arena.global().allocate(JAVA_INT, 4)

    fun record(scope: DrawScope) {
        requestedWidth = scope.size.width.toInt()
        requestedHeight = scope.size.height.toInt()
        val frame = image ?: return
        scope.drawIntoCanvas {
            it.nativeCanvas.drawImageRect(frame, Rect.makeWH(scope.size.width, scope.size.height))
        }
    }

    fun renderFrame(context: org.jetbrains.skia.DirectContext?) {
        if (context == null) return
        val width = requestedWidth
        val height = requestedHeight
        if (width <= 0 || height <= 0) return
        val gl = gl ?: Gl().also {
            gl = it
            mpv.createOpenGlRenderContext(Gl.GET_PROC_ADDRESS_CALLBACK, Gl.currentX11Display())
            mpv.onRenderUpdate { if (redrawPending.compareAndSet(false, true)) requestRedraw() }
            mpv.command("loadfile", video)
            context.resetGLAll()
        }
        redrawPending.set(false)
        var current = target
        if (current == null || current.width != width || current.height != height) {
            current?.let { gl.deleteFramebuffer(it.framebuffer) }
            current = gl.createTextureTarget(width, height)
            context.resetGLAll()
            target = current
            val previous = image
            image = Image.adoptTextureFrom(
                context,
                BackendTexture.makeGL(width, height, false, current.texture, Gl.TEXTURE_2D, Gl.RGBA8),
                SurfaceOrigin.TOP_LEFT, // mpv already renders upright into the FBO
                ColorType.RGBA_8888,
            )
            previous?.close() // Skia deletes the old texture once no recorded picture references it
            requestRedraw() // re-record so the picture references the new image
        }
        if (mpv.renderUpdate() and Mpv.UPDATE_FRAME != 0L) {
            fboParams.setAtIndex(JAVA_INT, 0, current.framebuffer)
            fboParams.setAtIndex(JAVA_INT, 1, width)
            fboParams.setAtIndex(JAVA_INT, 2, height)
            fboParams.setAtIndex(JAVA_INT, 3, 0)
            mpv.renderOpenGl(fboParams, false)
            context.resetGLAll() // mpv changed GL state behind Skia's back
            mpv.reportSwap()
            stats.videoFrames.incrementAndGet()
        }
    }
}

/** Software path: mpv converts/scales on the CPU into a buffer that Skia uploads every frame. */
@Composable
private fun SoftwareVideo(mpv: Mpv, video: String, stats: Stats, modifier: Modifier) {
    var frameTick by remember { mutableLongStateOf(0L) }
    val renderer = remember { SoftwareRenderer(mpv, stats) { SwingUtilities.invokeLater { frameTick++ } } }
    LaunchedEffect(Unit) { mpv.command("loadfile", video) }
    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION")
        frameTick
        stats.composeDraws.incrementAndGet()
        renderer.draw(this)
    }
}

private class SoftwareRenderer(private val mpv: Mpv, private val stats: Stats, private val requestRedraw: () -> Unit) {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "mpv-sw-render").apply { isDaemon = true } }
    private val lock = Any()
    @Volatile private var width = 0
    @Volatile private var height = 0
    private var buffer: MemorySegment = MemorySegment.NULL
    private var bufferWidth = 0
    private var bufferHeight = 0

    init {
        mpv.onRenderUpdate { worker.execute(::renderIfNeeded) }
    }

    private fun renderIfNeeded() {
        if (mpv.renderUpdate() and Mpv.UPDATE_FRAME == 0L) return
        val w = width
        val h = height
        if (w == 0 || h == 0) return
        synchronized(lock) {
            if (bufferWidth != w || bufferHeight != h) {
                buffer = Arena.global().allocate(w.toLong() * h * 4, 64) // spike: old buffers are leaked on resize
                bufferWidth = w
                bufferHeight = h
            }
            mpv.renderSoftware(buffer, w, h)
        }
        stats.videoFrames.incrementAndGet()
        requestRedraw()
    }

    fun draw(scope: DrawScope) {
        width = scope.size.width.toInt()
        height = scope.size.height.toInt()
        val frame = synchronized(lock) {
            if (bufferWidth == 0) return
            val info = ImageInfo(bufferWidth, bufferHeight, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
            Image.makeFromPixmap(Pixmap.make(info, buffer.address(), bufferWidth * 4))
        }
        scope.drawIntoCanvas {
            it.nativeCanvas.drawImageRect(frame, Rect.makeWH(scope.size.width, scope.size.height))
        }
        frame.close()
    }
}

private fun clock(seconds: Double): String = "%02d:%02d".format(seconds.toInt() / 60, seconds.toInt() % 60)
