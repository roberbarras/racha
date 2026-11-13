package io.github.roberbarras.racha.desktop

import androidx.compose.ui.unit.DpSize
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
import kotlinx.coroutines.launch
import java.awt.Dimension

fun main() {
    // Composition root: adapters are wired by hand, no reflection-based DI.
    val graph = DesktopGraph()
    val background = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    background.launch {
        graph.ensureBuiltInCategories()
        graph.keepLibraryUpToDate.run()
    }
    application {
        Window(
            onCloseRequest = {
                background.cancel()
                graph.close()
                exitApplication()
            },
            state = rememberWindowState(size = DpSize(1440.dp, 900.dp)),
            title = "Racha",
            icon = rememberVectorPainter(RachaLogo),
        ) {
            window.minimumSize = Dimension(480, 600)
            RachaApp(graph.dependencies)
        }
    }
}
