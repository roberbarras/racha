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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import io.github.roberbarras.racha.application.shared.AppLog
import io.github.roberbarras.racha.domain.preferences.Settings
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.Dimension
import java.awt.GraphicsEnvironment

fun main(args: Array<String>) {
    if ("--self-test" in args) kotlin.system.exitProcess(SelfTest.run())
    // One instance per session: a second launch hands its link (or just "come to the front") to the running one.
    val link = args.firstOrNull { it.startsWith("racha://") }.orEmpty()
    val claim = runCatching { SingleInstance.claim(link) }
    if (claim.isSuccess && claim.getOrNull() == null) return // the running instance has it
    claim.exceptionOrNull()?.let { AppLog.warn("app", "single-instance socket unavailable; continuing without it", it) }
    val instance = claim.getOrNull()
    val links = Channel<String>(Channel.BUFFERED).apply { if (link.isNotEmpty()) trySend(link) }
    val toFront = Channel<Unit>(Channel.CONFLATED)
    instance?.let { single ->
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            single.received.collect { message ->
                toFront.trySend(Unit)
                if (message.isNotEmpty()) links.trySend(message)
            }
        }
    }
    val pdfFonts = DesktopPaths.cache.resolve("pdfbox")
    PdfBoxDocument.configure(pdfFonts)
    // Composition root: adapters are wired by hand, no reflection-based DI.
    val graph = DesktopGraph(deepLinks = links.receiveAsFlow())
    reportCrashes(graph)
    graph.startSync()
    graph.startReminder()
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
    DesktopUiScale.apply(runBlocking { graph.preferences.get(Settings.UiScale) })
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
                instance?.close()
                exitApplication()
            },
            state = rememberWindowState(size = initialSize, position = WindowPosition(Alignment.Center)),
            title = "Racha",
            icon = rememberVectorPainter(RachaLogo),
        ) {
            // Settings > General > Interface size: applied at once (0 = what the desktop announces).
            val chosen by graph.preferences.observe(Settings.UiScale).collectAsState(null)
            val scale = remember(chosen) { chosen?.let(DesktopUiScale::apply); DesktopUiScale.current }
            window.minimumSize = Dimension((MIN_WIDTH_DP * scale).toInt(), (MIN_HEIGHT_DP * scale).toInt())
            graph.playerHooks.attach(window)
            LaunchedEffect(Unit) {
                for (request in toFront) {
                    window.isMinimized = false
                    window.toFront()
                    window.requestFocus()
                }
            }
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
        AppLog.error("crash", "uncaught on ${thread.name}", error)
        runCatching { runBlocking { withTimeoutOrNull(2_000) { graph.telemetry.error(error, mapOf("thread" to thread.name)) } } }
        if (previous != null) previous.uncaughtException(thread, error) else error.printStackTrace()
    }
}
