package io.github.roberbarras.racha.desktop.resource

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import io.github.roberbarras.racha.desktop.DesktopPaths
import io.github.roberbarras.racha.desktop.DesktopUiScale
import io.github.roberbarras.racha.domain.library.RelativePath
import io.github.roberbarras.racha.domain.resource.ExternalLinks
import javafx.application.Platform
import javafx.embed.swing.JFXPanel
import javafx.scene.Scene
import javafx.scene.web.WebView
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.nio.file.Path
import javax.swing.SwingUtilities

/**
 * A course web page in JavaFX's WebKit (ADR-0003), embedded in Compose through Swing. The page comes from a loopback
 * server that only serves its course folder; navigation that leaves the course and popups go to the system browser.
 * JavaScript stays on (quizzes) without any bridge to the app.
 */
@Composable
internal fun JavaFxWebView(folder: String, page: RelativePath, onExternalLink: (String) -> Unit, modifier: Modifier) {
    val server = remember(folder) { CourseFileServer(Path.of(folder)) }
    val panel = remember(folder, page) {
        JavaFx.prepare()
        JFXPanel()
    }
    SwingPanel(modifier = modifier, factory = { panel })
    DisposableEffect(server, panel) {
        // The scene is created once the panel has its real size: JFXPanel's pixel transfer breaks
        // (BufferOverflowException, then a blank panel) when the scene starts while Compose is still sizing it.
        var started = false
        val sized = object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                if (started || panel.width <= 0 || panel.height <= 0) return
                started = true
                Platform.runLater { panel.scene = Scene(webView(server, page, onExternalLink) { repaintSoon(panel) }) }
            }
        }
        panel.addComponentListener(sized)
        SwingUtilities.invokeLater { sized.componentResized(ComponentEvent(panel, ComponentEvent.COMPONENT_RESIZED)) }
        onDispose {
            panel.removeComponentListener(sized)
            Platform.runLater {
                (panel.scene?.root as? WebView)?.engine?.load("about:blank")
                panel.scene = null
            }
            server.close()
        }
    }
}

internal object JavaFx {
    private var prepared = false

    /**
     * Before JavaFX starts: render at 1 like the Swing panel that hosts it; the app's UI scale is applied as the
     * WebView zoom. (JavaFX 27.0.1's JFXPanel never paints here — BufferOverflowException, blank page — so the
     * build pins 26.0.2; see the version catalog.)
     */
    fun prepare() {
        if (prepared) return
        prepared = true
        System.setProperty("glass.gtk.uiScale", "1")
        System.setProperty("prism.allowhidpi", "false")
        Platform.setImplicitExit(false) // closing the last viewer must not shut JavaFX down for good
    }
}

/** A static page is painted once; make sure that paint happens after the page is laid out. */
private fun repaintSoon(panel: JFXPanel) {
    SwingUtilities.invokeLater(panel::repaint)
    javax.swing.Timer(300) { panel.repaint() }.apply { isRepeats = false; start() }
}

private fun webView(server: CourseFileServer, page: RelativePath, onExternalLink: (String) -> Unit, onLoaded: () -> Unit): WebView {
    val view = WebView()
    // JavaFX inside Swing draws at 100 %: follow the app's UI scale (4K at 170 % would be tiny otherwise).
    view.zoom = DesktopUiScale.current.toDouble()
    val engine = view.engine
    engine.isJavaScriptEnabled = true
    engine.userDataDirectory = DesktopPaths.cache.resolve("webview").toFile()
    engine.setCreatePopupHandler { request -> null } // window.open / target=_blank: refused here, see below
    val start = server.urlFor(page.value)
    engine.locationProperty().addListener { _, previous, location ->
        if (location == null || server.serves(location) || location == "about:blank") return@addListener
        // Leaving the course: show it in the system browser (web/mail only) and stay on the course page.
        if (ExternalLinks.isSafe(location)) onExternalLink(location)
        Platform.runLater { engine.load(previous?.takeIf(server::serves) ?: start) }
    }
    engine.loadWorker.stateProperty().addListener { _, _, state -> if (state == javafx.concurrent.Worker.State.SUCCEEDED) onLoaded() }
    engine.load(start)
    return view
}
