package io.github.roberbarras.racha.ui

import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import io.github.roberbarras.racha.ui.app.RachaApp
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test

/** Personal statistics (spec §2.2) from the demo library: this device only, no account. */
@OptIn(ExperimentalTestApi::class)
class StatsUiTest {
    private val originalLocale = Locale.getDefault()
    private val out = java.io.File(System.getProperty("racha.screenshots") ?: "build/screenshots").apply { mkdirs() }

    @AfterTest
    fun restoreLocale() = Locale.setDefault(originalLocale)

    @Test
    fun progressShowsTimeStreaksActivityAndCategories() = runDesktopComposeUiTest(1440, 900) {
        Locale.setDefault(Locale.ENGLISH)
        val deps = DemoLibrary.dependencies()
        setContent {
            val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) { RachaApp(deps) }
        }
        waitUntil(timeoutMillis = 30_000) { onAllNodesWithText("Progress").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Progress").performClick()
        waitUntil(timeoutMillis = 30_000) { onAllNodesWithText("Day streak").fetchSemanticsNodes().isNotEmpty() }

        onNodeWithText("Time watched on this device. Sign in to sync to add up all your devices.").assertExists()
        onNode(hasText("7") and hasText("Day streak")).assertExists() // the tile merges its value and label
        onNodeWithText("Total watched").assertExists()
        onNode(hasContentDescription("active days in the last year", substring = true)).assertExists()
        // Further down the list (only what is on screen is composed): scroll to it.
        onNode(hasScrollToNodeAction()).performScrollToNode(hasText("TIME BY CATEGORY")) // section headers are shown in capitals
        onAllNodesWithText("Courses").assertCountEquals(2) // the sidebar category and the bar
        onNodeWithText("Series").assertExists()
    }
    @Test
    fun theBreakdownsShowWhenHowAndWhatIsWatched() = runDesktopComposeUiTest(1100, 3400) {
        Locale.setDefault(Locale.forLanguageTag("es"))
        val deps = DemoLibrary.dependencies()
        setContent {
            val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                io.github.roberbarras.racha.ui.design.RachaTheme(dark = false) {
                    androidx.compose.foundation.layout.Box(
                        androidx.compose.ui.Modifier.fillMaxSize().background(io.github.roberbarras.racha.ui.design.Racha.colors.background),
                    ) { io.github.roberbarras.racha.ui.stats.StatsScreen(deps, {}, null) }
                }
            }
        }
        waitUntil(timeoutMillis = 30_000) { onAllNodesWithText("SESIONES").fetchSemanticsNodes().isNotEmpty() }
        listOf("ÚLTIMAS 12 SEMANAS", "POR HORA DEL DÍA", "POR DÍA DE LA SEMANA", "CURSOS Y SERIES", "VISTO RECIENTEMENTE").forEach {
            onNodeWithText(it).assertExists()
        }
        onNodeWithText("46").assertExists() // sessions
        onNodeWithText("1.4×").assertExists() // 50 400 / 36 000
        onNodeWithText("46 %").assertExists() // 21 of 46 with subtitles
        javax.imageio.ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", java.io.File(out, "desktop-stats-breakdowns-es.png"))
    }
}
