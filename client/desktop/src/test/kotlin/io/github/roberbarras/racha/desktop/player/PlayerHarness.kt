package io.github.roberbarras.racha.desktop.player

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.navigation.compose.rememberNavController
import io.github.roberbarras.racha.desktop.DesktopDatabase
import io.github.roberbarras.racha.desktop.DesktopGraph
import io.github.roberbarras.racha.domain.learning.Progress
import io.github.roberbarras.racha.domain.library.CategoryKey
import io.github.roberbarras.racha.domain.library.CategoryRoot
import io.github.roberbarras.racha.domain.library.ContentId
import io.github.roberbarras.racha.domain.library.RelativePath
import io.github.roberbarras.racha.domain.library.RootKey
import io.github.roberbarras.racha.domain.playback.StartMode
import io.github.roberbarras.racha.files.NioLibrarySource
import io.github.roberbarras.racha.ui.app.PlayerRoute
import io.github.roberbarras.racha.ui.app.RachaApp
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Window as AwtWindow
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.imageio.ImageIO
import kotlin.system.exitProcess
import kotlin.time.Clock

/**
 * End-to-end check of the Linux player in a real window: the app opens a lesson from the library, mpv plays it
 * inside the window, the controls overlay sits exactly over the player (also in fullscreen and picture-in-picture),
 * a frame capture is written and the position is saved on leaving. Run with `spikes/safe-run.sh virtual 120
 * desktop-player` from `client/` (throwaway home directory; Xvfb unless the desktop owner agrees otherwise).
 * Prints one `RESULT {...}` line; exit code 0 only if every check passed.
 */
fun main() {
    val home = Path.of(System.getProperty("user.home"))
    val shots = Files.createDirectories(home.resolve("shots"))
    val course = home.resolve("library/Kotlin Course")
    val lessonPath = "01 - Introduction/001 Kotlin Basics.mp4"
    Files.createDirectories(course.resolve("01 - Introduction"))
    Files.copy(Path.of("testdata/media/long.mp4"), course.resolve(lessonPath), StandardCopyOption.REPLACE_EXISTING)
    Files.writeString(course.resolve("01 - Introduction/001 Kotlin Basics_es.srt"), "1\n00:00:00,000 --> 00:00:30,000\nHola desde Racha\n")
    Files.copy(Path.of("testdata/media/long.mp4"), course.resolve("01 - Introduction/002 Variables.mp4"), StandardCopyOption.REPLACE_EXISTING)

    val graph = DesktopGraph(DesktopDatabase.open(home.resolve("racha.db")))
    val root = RootKey("6f0c3a51-9d4e-4f8e-8f0e-3b7a2c1d5e90")
    val lesson = ContentId.forVideo(CategoryKey.COURSES, root, RelativePath.of(lessonPath))
    runBlocking {
        graph.ensureBuiltInCategories()
        graph.database.categories.upsertRoot(CategoryRoot(root, CategoryKey.COURSES, NioLibrarySource.locationOf(course), 0))
        graph.scanLibrary.category(CategoryKey.COURSES)
        val now = Clock.System.now()
        graph.database.progress.save(Progress.start(lesson, 30, now).recordPosition(12, 30, now))
    }

    val checks = linkedMapOf<String, Any?>()
    application {
        Window(onCloseRequest = ::exitApplication, state = rememberWindowState(size = DpSize(1280.dp, 720.dp)), title = "Racha") {
            graph.playerHooks.attach(window)
            val nav = rememberNavController()
            RachaApp(graph.dependencies, nav)
            LaunchedEffect(Unit) {
                val ok = runCatching {
                    delay(1_500)
                    System.err.println("STEP navigate")
                    nav.navigate(PlayerRoute(lesson.value, StartMode.RESUME))
                    val hooks = graph.playerHooks
                    waitFor("playing", 20_000) { hooks.currentEngine?.snapshot?.value?.playing == true }
                    val engine = hooks.currentEngine!!
                    checks["resumedAtMs"] = engine.positionMs()
                    check(engine.positionMs() >= 11_000) { "did not resume: ${engine.positionMs()}" }
                    delay(1_000)

                    fun overlay(): AwtWindow = window.ownedWindows.first { it is ComposeDialog && it.isShowing }
                    fun playerArea(): Rectangle = window.contentPane.let { Rectangle(it.locationOnScreen, it.size) }
                    fun aligned(): Boolean = overlay().bounds.near(playerArea())
                    waitFor("overlay aligned", 5_000, ::aligned)
                    checks["overlay"] = overlay().bounds.text()
                    checks["playerArea"] = playerArea().text()
                    capture(shots.resolve("window.png"), window.bounds)

                    System.err.println("STEP fullscreen"); hooks.setFullscreen(true)
                    delay(1_500)
                    checks["fullscreenArea"] = playerArea().text()
                    waitFor("overlay follows fullscreen", 5_000, ::aligned)
                    hooks.setFullscreen(false)
                    delay(1_000)
                    checks["positionAfterFullscreenMs"] = engine.positionMs()

                    System.err.println("STEP pip"); hooks.enterPictureInPicture(160, 90)
                    waitFor("picture-in-picture window", 5_000) { window.width <= 600 && aligned() }
                    checks["pipWindow"] = window.bounds.text()
                    checks["pipOnTop"] = window.isAlwaysOnTop
                    capture(shots.resolve("pip.png"), window.bounds)
                    hooks.exitPictureInPicture()
                    waitFor("restored from PiP", 5_000) { window.width > 1000 && aligned() }
                    checks["playingAfterPip"] = engine.snapshot.value.playing
                    checks["positionAfterPipMs"] = engine.positionMs()
                    checks["snapshotAfterPip"] = engine.snapshot.value

                    val frame = hooks.screenshot(engine, "harness-frame")
                    checks["frameCapture"] = frame
                    check(frame != null && Files.size(Path.of(frame)) > 0) { "no frame capture" }

                    val before = engine.positionMs()
                    checks["positionBeforeLeavingMs"] = before
                    check(before >= 14_000) { "playback did not advance: $before" }
                    System.err.println("STEP leave"); nav.popBackStack()
                    waitFor("position saved on leaving", 10_000) {
                        runBlocking { graph.database.progress.get(lesson)?.positionSec ?: 0 } >= before / 1000 - 1
                    }
                    checks["savedSec"] = runBlocking { graph.database.progress.get(lesson)?.positionSec }
                    capture(shots.resolve("after.png"), window.bounds)

                    // Closing the window mid-playback (what Main does on close) still saves the position.
                    System.err.println("STEP reopen and close window")
                    nav.navigate(PlayerRoute(lesson.value, StartMode.RESUME))
                    waitFor("playing again", 20_000) { hooks.currentEngine?.let { it !== engine && it.snapshot.value.playing } == true }
                    val savedBefore = (checks["savedSec"] as Long) * 1000
                    waitFor("advanced after reopening", 15_000) { hooks.currentEngine!!.positionMs() >= savedBefore + 3_000 }
                    val reopened = hooks.currentEngine!!.positionMs()
                    checks["positionAtWindowCloseMs"] = reopened
                    hooks.finishOpenSession()
                    val onClose = runBlocking { graph.database.progress.get(lesson)?.positionSec ?: 0 }
                    checks["savedOnWindowCloseSec"] = onClose
                    check(onClose >= reopened / 1000 - 1) { "not saved on close: $onClose" }
                }
                checks["error"] = ok.exceptionOrNull()?.let { "${it::class.simpleName}: ${it.message}" }
                println("RESULT " + checks.entries.joinToString(", ", "{", "}") { (k, v) -> "\"$k\": \"$v\"" })
                graph.close()
                exitProcess(if (ok.isSuccess) 0 else 1)
            }
        }
    }
}

private suspend fun waitFor(what: String, timeoutMs: Long, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!condition()) {
        check(System.currentTimeMillis() < deadline) { "timed out: $what" }
        delay(100)
    }
}

private fun Rectangle.near(other: Rectangle, tolerance: Int = 2) =
    kotlin.math.abs(x - other.x) <= tolerance && kotlin.math.abs(y - other.y) <= tolerance &&
        kotlin.math.abs(width - other.width) <= tolerance && kotlin.math.abs(height - other.height) <= tolerance

private fun Rectangle.text() = "${x},${y} ${width}x${height}"

private fun capture(file: Path, area: Rectangle) {
    ImageIO.write(Robot().createScreenCapture(area), "png", file.toFile())
}
