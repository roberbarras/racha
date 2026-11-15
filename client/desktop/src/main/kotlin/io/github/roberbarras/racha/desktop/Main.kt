package io.github.roberbarras.racha.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import io.github.roberbarras.racha.ui.app.RachaApp
import io.github.roberbarras.racha.ui.design.RachaLogo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import io.github.roberbarras.racha.desktop.resource.PdfBoxDocument
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.Dimension
import java.awt.GraphicsEnvironment

fun main() {
    val pdfFonts = DesktopPaths.cache.resolve("pdfbox")
    PdfBoxDocument.configure(pdfFonts)
    // Composition root: adapters are wired by hand, no reflection-based DI.
    val graph = DesktopGraph()
    reportCrashes(graph)
    graph.startSync()
    val background = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    background.launch {
        graph.ensureBuiltInCategories()
        graph.keepLibraryUpToDate.run()
    }
    if (!PdfBoxDocument.fontIndexExists(pdfFonts)) {
        // Once per installation, after start-up has settled: index fonts so the first PDF opens at once.
        background.launch(Dispatchers.IO) {
            delay(PDF_FONT_INDEX_DELAY_MS)
            runCatching { PdfBoxDocument.buildFontIndex() }
        }
    }
    val scale = DesktopUiScale.current
    // Initial window: a comfortable share of the screen it opens on, whatever its size or density.
    val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
    val initialSize = DpSize((screen.width * INITIAL_SHARE).dp, (screen.height * INITIAL_SHARE).dp)
    application {
        Window(
            onCloseRequest = {
                // Closing mid-playback: save the position before the process ends (bounded, never hangs the exit).
                runBlocking { withTimeoutOrNull(3_000) { graph.playerHooks.finishOpenSession() } }
                background.cancel()
                graph.close()
                exitApplication()
            },
            state = rememberWindowState(size = initialSize, position = WindowPosition(Alignment.Center)),
            title = "Racha",
            icon = rememberVectorPainter(RachaLogo),
        ) {
            window.minimumSize = Dimension((MIN_WIDTH_DP * scale).toInt(), (MIN_HEIGHT_DP * scale).toInt())
            graph.playerHooks.attach(window)
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density * scale, base.fontScale)) {
                RachaApp(graph.dependencies)
            }
        }
    }
}

private const val INITIAL_SHARE = 0.8f
private const val MIN_WIDTH_DP = 480
private const val MIN_HEIGHT_DP = 560
private const val PDF_FONT_INDEX_DELAY_MS = 30_000L

/** Uncaught errors (any thread, the UI one included) go to the account's server (§8) before the usual handling. */
private fun reportCrashes(graph: DesktopGraph) {
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
        runCatching { runBlocking { withTimeoutOrNull(2_000) { graph.telemetry.error(error, mapOf("thread" to thread.name)) } } }
        if (previous != null) previous.uncaughtException(thread, error) else error.printStackTrace()
    }
}
