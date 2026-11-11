package io.github.roberbarras.racha.desktop

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import io.github.roberbarras.racha.application.environment.DescribeEnvironment
import io.github.roberbarras.racha.ui.App

fun main() {
    // Composition root: adapters are wired by hand, no reflection-based DI.
    val describeEnvironment = DescribeEnvironment { System.getProperty("os.name") }
    application {
        Window(onCloseRequest = ::exitApplication, title = "Racha") {
            App(greeting = describeEnvironment())
        }
    }
}
