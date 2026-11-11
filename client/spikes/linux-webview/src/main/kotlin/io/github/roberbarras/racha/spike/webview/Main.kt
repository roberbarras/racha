package io.github.roberbarras.racha.spike.webview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.io.File
import java.nio.file.Path
import java.util.Locale
import javafx.application.Platform
import javafx.concurrent.Worker
import javafx.embed.swing.JFXPanel
import javafx.scene.Scene
import javafx.scene.web.WebView
import kotlin.system.exitProcess

/** Usage: `<courseRoot> <relativeHtmlPath>`. Prints `RESULT ...` lines and exits after the page's checks run. */
fun main(args: Array<String>) {
    val server = CourseFileServer(Path.of(args[0]))
    val pageUrl = server.urlFor(args[1])
    val startNanos = System.nanoTime()
    Platform.setImplicitExit(false)

    application {
        Window(onCloseRequest = ::exitApplication, state = rememberWindowState(size = DpSize(1280.dp, 720.dp)), title = "racha-spike-webview") {
            val text = TextStyle(color = Color(0xFFF2EFE9), fontSize = 14.sp)
            Row(Modifier.fillMaxSize()) {
                SwingPanel(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    factory = { JFXPanel().also { panel -> Platform.runLater { attachWebView(panel, server, pageUrl, startNanos) } } },
                )
                Column(Modifier.width(260.dp).fillMaxHeight().background(Color(0xFF14161A)).padding(12.dp)) {
                    listOf("001 Course Introduction", "002 Creating an AWS Account", "▶ 003 AWS Account Activation…", "004 …")
                        .forEach { BasicText(it, Modifier.padding(vertical = 6.dp), style = text) }
                }
            }
        }
    }
}

private fun attachWebView(panel: JFXPanel, server: CourseFileServer, pageUrl: String, startNanos: Long) {
    val webView = WebView()
    val engine = webView.engine
    engine.isJavaScriptEnabled = true
    engine.userDataDirectory = File(System.getProperty("java.io.tmpdir"), "racha-webview-spike")
    // Popups and navigation outside the course server are refused; the app opens them in the system browser.
    engine.setCreatePopupHandler { request -> println("RESULT popupBlocked"); null }
    engine.locationProperty().addListener { _, previous, location ->
        if (!location.startsWith(server.baseUrl)) {
            println("RESULT externalNavigationIntercepted=$location")
            Platform.runLater { engine.loadWorker.cancel(); if (previous != null) engine.load(previous) }
        }
    }
    engine.loadWorker.stateProperty().addListener { _, _, state ->
        if (state == Worker.State.SUCCEEDED && engine.location == pageUrl) {
            println("RESULT firstLoadMs=%.0f".format(Locale.ROOT, (System.nanoTime() - startNanos) / 1e6))
            pollChecks(engine)
        }
    }
    panel.scene = Scene(webView)
    engine.load(pageUrl)
}

/** The fixture page writes its checks into #results; wait for "done" then print and exit. */
private fun pollChecks(engine: javafx.scene.web.WebEngine, attempt: Int = 0) {
    val results = engine.executeScript("document.getElementById('results').dataset.done === 'true' ? document.getElementById('results').innerText : ''") as String
    if (results.isNotEmpty() || attempt > 50) {
        results.lines().filter(String::isNotBlank).forEach { println("RESULT check $it") }
        val rssMiB = File("/proc/self/status").readLines().first { it.startsWith("VmHWM:") }.filter(Char::isDigit).toLong() / 1024
        println("RESULT rssMiB=$rssMiB")
        Thread {
            Thread.sleep(3000) // leave time for a screenshot
            exitProcess(0)
        }.start()
        return
    }
    Thread {
        Thread.sleep(100)
        Platform.runLater { pollChecks(engine, attempt + 1) }
    }.start()
}
