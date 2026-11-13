package io.github.roberbarras.racha.desktop

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import io.github.roberbarras.racha.application.environment.DescribeEnvironment
import io.github.roberbarras.racha.ui.App
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

fun main() {
    // Composition root: adapters are wired by hand, no reflection-based DI.
    val graph = DesktopGraph()
    val background = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    background.launch {
        graph.ensureBuiltInCategories()
        graph.keepLibraryUpToDate.run()
    }
    val describeEnvironment = DescribeEnvironment { System.getProperty("os.name") }
    application {
        Window(
            onCloseRequest = {
                background.cancel()
                graph.close()
                exitApplication()
            },
            title = "Racha",
        ) {
            App(greeting = describeEnvironment())
        }
    }
}
