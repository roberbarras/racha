package io.github.roberbarras.racha.desktop.resource

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.navigation.compose.rememberNavController
import io.github.roberbarras.racha.desktop.DesktopDatabase
import io.github.roberbarras.racha.desktop.DesktopGraph
import io.github.roberbarras.racha.domain.library.CategoryKey
import io.github.roberbarras.racha.domain.library.CategoryRoot
import io.github.roberbarras.racha.domain.library.ContentId
import io.github.roberbarras.racha.domain.library.RelativePath
import io.github.roberbarras.racha.domain.library.RootKey
import io.github.roberbarras.racha.files.NioLibrarySource
import io.github.roberbarras.racha.ui.app.RachaApp
import io.github.roberbarras.racha.ui.app.ResourceRoute
import javafx.application.Platform
import javafx.embed.swing.JFXPanel
import javafx.scene.web.WebView
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.awt.Component
import java.awt.Container
import java.awt.Robot
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.system.exitProcess

/**
 * End-to-end check of the Linux resource viewer (spec §5, ADR-0003) in a real window: the web test course's page
 * runs its own checks inside JavaFX WebKit served by the course server, then a PDF and a text file open, and leaving
 * marks the page viewed. Run with `spikes/safe-run.sh virtual 120 desktop-resources` from `client/`.
 */
fun main() {
    val home = Path.of(System.getProperty("user.home"))
    PdfBoxDocument.configure(home.resolve(".cache/racha/pdfbox"))
    PdfBoxDocument.buildFontIndex() // what the app does once per installation, so the PDF step measures rendering
    val shots = Files.createDirectories(home.resolve("shots"))
    val library = home.resolve("library")
    copyTree(Path.of("testdata/web/AWS Course"), library.resolve("Web Course"))
    copyTree(Path.of("testdata/web/Other Course"), library.resolve("Other Course"))
    Files.copy(Path.of("testdata/documents/sample.pdf"), library.resolve("Web Course/02 - Code & Slides Download/Slides.pdf"))

    val graph = DesktopGraph(DesktopDatabase.open(home.resolve("racha.db")))
    val root = RootKey("8a2b6c1d-4e5f-4a7b-9c0d-1e2f3a4b5c6d")
    fun id(path: String) = ContentId.forResource(CategoryKey.COURSES, root, RelativePath.of(path))
    val page = id("Web Course/01 - Introduction/003 AWS Account Activation Troubleshooting.html")
    val pdf = id("Web Course/02 - Code & Slides Download/Slides.pdf")
    val text = id("Web Course/02 - Code & Slides Download/external-links.txt")
    runBlocking {
        graph.ensureBuiltInCategories()
        graph.database.categories.upsertRoot(CategoryRoot(root, CategoryKey.COURSES, NioLibrarySource.locationOf(library), 0))
        graph.scanLibrary.category(CategoryKey.COURSES)
    }

    val checks = linkedMapOf<String, Any?>()
    application {
        Window(onCloseRequest = ::exitApplication, state = rememberWindowState(size = DpSize(1280.dp, 800.dp)), title = "Racha") {
            val nav = rememberNavController()
            RachaApp(graph.dependencies, nav)
            LaunchedEffect(Unit) {
                val ok = runCatching {
                    delay(1_500)
                    nav.navigate(ResourceRoute(page.value))
                    val started = System.nanoTime()
                    var results = ""
                    waitFor("page checks", 60_000) {
                        results = webView(window)?.let { pageResults(it) }.orEmpty()
                        results.isNotEmpty()
                    }
                    checks["firstLoadMs"] = (System.nanoTime() - started) / 1_000_000
                    val lines = results.lines().filter(String::isNotBlank).associate { it.substringBefore('=') to it.substringAfter('=') }
                    checks["webChecks"] = lines
                    listOf("javascript", "relativeCss", "relativeImage", "quizInteraction", "sameCourseFile").forEach { check(lines[it] == "ok") { "$it: ${lines[it]}" } }
                    listOf("fileUrlRead", "encodedTraversal", "siblingCourse", "absoluteEscape").forEach { check(lines[it]?.startsWith("blocked") == true) { "$it leaked" } }
                    delay(3_000)
                    capture(shots.resolve("html.png"), window)

                    nav.navigate(ResourceRoute(pdf.value)) { popUpTo<ResourceRoute> { inclusive = true } }
                    delay(4_000)
                    capture(shots.resolve("pdf.png"), window)
                    nav.navigate(ResourceRoute(text.value)) { popUpTo<ResourceRoute> { inclusive = true } }
                    delay(2_000)
                    capture(shots.resolve("text.png"), window)
                    nav.popBackStack()

                    waitFor("viewed on leaving", 10_000) {
                        runBlocking { listOf(page, pdf, text).all { graph.database.progress.get(it)?.completed == true } }
                    }
                    checks["viewed"] = "page, pdf, text"
                }
                checks["error"] = ok.exceptionOrNull()?.let { "${it::class.simpleName}: ${it.message}" }
                println("RESULT " + checks.entries.joinToString(", ", "{", "}") { (k, v) -> "\"$k\": \"$v\"" })
                graph.close()
                exitProcess(if (ok.isSuccess) 0 else 1)
            }
        }
    }
}

private fun copyTree(from: Path, to: Path) {
    Files.walk(from).use { paths ->
        paths.forEach { source ->
            val target = to.resolve(from.relativize(source).toString())
            if (Files.isDirectory(source)) Files.createDirectories(target) else Files.copy(source, target)
        }
    }
}

private fun webView(component: Component): WebView? = when (component) {
    is JFXPanel -> component.scene?.root as? WebView
    is Container -> component.components.firstNotNullOfOrNull { webView(it) }
    else -> null
}

/** The page's `#results` once it has finished its checks, read on the JavaFX thread. */
private fun pageResults(view: WebView): String {
    val result = CompletableFuture<String>()
    Platform.runLater {
        result.complete(runCatching {
            view.engine.executeScript(
                "(function(){var r=document.getElementById('results');return r&&r.dataset.done==='true'?r.innerText:''})()",
            ) as? String
        }.getOrNull().orEmpty())
    }
    return runCatching { result.get(2, TimeUnit.SECONDS) }.getOrDefault("")
}

private suspend fun waitFor(what: String, timeoutMs: Long, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!condition()) {
        check(System.currentTimeMillis() < deadline) { "timed out: $what" }
        delay(200)
    }
}

private fun capture(file: Path, window: java.awt.Window) {
    ImageIO.write(Robot().createScreenCapture(window.bounds), "png", file.toFile())
}
