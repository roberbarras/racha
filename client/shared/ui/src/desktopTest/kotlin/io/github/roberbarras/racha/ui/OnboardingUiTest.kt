package io.github.roberbarras.racha.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import io.github.roberbarras.racha.domain.library.Mode
import io.github.roberbarras.racha.domain.preferences.AppLanguage
import io.github.roberbarras.racha.domain.preferences.Settings
import io.github.roberbarras.racha.ui.app.RachaApp
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** First run (spec §10.3): guided, every step optional, never shown again; an existing library skips it. */
@OptIn(ExperimentalTestApi::class)
class OnboardingUiTest {
    private val originalLocale = Locale.getDefault()
    private val out = File(System.getProperty("racha.screenshots") ?: "build/screenshots").apply { mkdirs() }

    @AfterTest
    fun restoreLocale() = Locale.setDefault(originalLocale)

    private fun androidx.compose.ui.test.ComposeUiTest.app(deps: io.github.roberbarras.racha.ui.app.RachaDependencies) = setContent {
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        CompositionLocalProvider(LocalViewModelStoreOwner provides owner) { RachaApp(deps) }
    }

    @Test
    fun aFirstRunIsGuidedAndThenGone() = runDesktopComposeUiTest(1440, 900) {
        Locale.setDefault(Locale.ENGLISH)
        val deps = DemoLibrary.dependencies(empty = true)
        app(deps)
        waitUntil(timeoutMillis = 30_000) { onAllNodesWithText("Welcome to Racha").fetchSemanticsNodes().isNotEmpty() }
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "desktop-onboarding-welcome-en.png"))

        onNodeWithText("Español").performClick()
        waitUntil(timeoutMillis = 30_000) { onAllNodesWithText("Te damos la bienvenida a Racha").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Siguiente").performClick()
        waitUntil(timeoutMillis = 30_000) { onAllNodesWithText("¿Para qué la vas a usar?").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Ocio").performClick()
        waitUntil(timeoutMillis = 30_000) { runBlocking { deps.preferences.get(Settings.Mode) } == Mode.LEISURE }
        onNodeWithText("Siguiente").performClick()
        waitUntil(timeoutMillis = 30_000) { onAllNodesWithText("Elige tus carpetas").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(onAllNodesWithText("Añadir carpeta").fetchSemanticsNodes().size >= 3, "one per category")
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "desktop-onboarding-folders-es.png"))
        onNodeWithText("Siguiente").performClick()
        waitUntil(timeoutMillis = 30_000) { onAllNodesWithText("Un empujón diario").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Empezar").performClick()

        waitUntil(timeoutMillis = 30_000) { onAllNodesWithText("Te damos la bienvenida a Racha").fetchSemanticsNodes().isEmpty() && onAllNodesWithText("Inicio").fetchSemanticsNodes().isNotEmpty() }
        runBlocking {
            assertEquals(true, deps.preferences.get(Settings.OnboardingDone))
            assertEquals(AppLanguage.ES, deps.preferences.get(Settings.Language))
        }
    }

    @Test
    fun anExistingLibraryNeverSeesIt() = runDesktopComposeUiTest(1440, 900) {
        Locale.setDefault(Locale.ENGLISH)
        val deps = DemoLibrary.dependencies()
        app(deps)
        waitUntil(timeoutMillis = 30_000) { onAllNodesWithText("12:34 / 45:10").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(onAllNodesWithText("Welcome to Racha").fetchSemanticsNodes().isEmpty())
        waitUntil(timeoutMillis = 30_000) { runBlocking { deps.preferences.get(Settings.OnboardingDone) } }
    }
}
