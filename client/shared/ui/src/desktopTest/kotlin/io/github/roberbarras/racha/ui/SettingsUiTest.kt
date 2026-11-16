package io.github.roberbarras.racha.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import io.github.roberbarras.racha.domain.preferences.AppLanguage
import io.github.roberbarras.racha.domain.preferences.Settings
import io.github.roberbarras.racha.domain.preferences.ThemeMode
import io.github.roberbarras.racha.ui.app.RachaApp
import io.github.roberbarras.racha.ui.app.RachaDependencies
import io.github.roberbarras.racha.ui.app.SettingsRoute
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Settings (spec §9): applied as they change, the language included. */
@OptIn(ExperimentalTestApi::class)
class SettingsUiTest {
    private val originalLocale = Locale.getDefault()
    private val out = File(System.getProperty("racha.screenshots") ?: "build/screenshots").apply { mkdirs() }

    @AfterTest
    fun restoreLocale() = Locale.setDefault(originalLocale)

    private fun open(width: Int, height: Int, language: String, deps: RachaDependencies = DemoLibrary.dependencies(), dark: Boolean = false, body: androidx.compose.ui.test.ComposeUiTest.(RachaDependencies) -> Unit) =
        runDesktopComposeUiTest(width, height) {
            Locale.setDefault(Locale.forLanguageTag(language))
            if (dark) runBlocking { deps.preferences.set(Settings.Theme, ThemeMode.DARK) }
            var nav: NavHostController? = null
            setContent {
                val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
                val controller = rememberNavController().also { nav = it }
                CompositionLocalProvider(LocalViewModelStoreOwner provides owner) { RachaApp(deps, controller) }
            }
            waitUntil(timeoutMillis = 30_000) { onAllNodesWithText("12:34 / 45:10").fetchSemanticsNodes().isNotEmpty() }
            runOnIdle { nav!!.navigate(SettingsRoute) }
            body(deps)
        }

    @Test
    fun languageThemeAndSwitchesApplyAtOnce() = open(1440, 900, "en") { deps ->
        waitUntil(timeoutMillis = 30_000) { onAllNodesWithText("Everything is saved as you change it.").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Español").performClick()
        waitUntil(timeoutMillis = 30_000) { onAllNodesWithText("Todo se guarda al momento.").fetchSemanticsNodes().isNotEmpty() }
        runBlocking { assertEquals(AppLanguage.ES, deps.preferences.get(Settings.Language)) }
        onNodeWithText("Inicio").assertExists() // the whole app follows, not only this screen

        onNodeWithText("Oscuro").performClick()
        waitUntil(timeoutMillis = 30_000) { runBlocking { deps.preferences.get(Settings.Theme) } == ThemeMode.DARK }

        onNode(hasScrollToIndexAction()).performScrollToNode(hasContentDescription("Reproducir el siguiente automáticamente"))
        onNode(hasContentDescription("Reproducir el siguiente automáticamente")).performClick()
        waitUntil(timeoutMillis = 30_000) { runBlocking { !deps.preferences.get(Settings.AutoplayNext) } }

        onNodeWithText("English").let { onNode(hasScrollToIndexAction()).performScrollToNode(hasText("English")); it.performClick() }
        waitUntil(timeoutMillis = 30_000) { onAllNodesWithText("Everything is saved as you change it.").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun aCategoryCanBeCreatedFromSettings() = open(1440, 900, "en") { deps ->
        waitUntil(timeoutMillis = 30_000) { onAllNodesWithText("Add category").fetchSemanticsNodes().isNotEmpty() }
        onNode(hasScrollToIndexAction()).performScrollToNode(hasContentDescription("New category name"))
        onNode(hasContentDescription("New category name")).performTextInput("Documentaries")
        onNodeWithText("Add category").performClick()
        waitUntil(timeoutMillis = 30_000) { runBlocking { deps.categories.all().any { it.name == "Documentaries" } } }
        assertTrue(onAllNodesWithText("Documentaries").fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun desktopSettingsEs() = open(1440, 900, "es") { shoot("desktop-settings-es") }
    @Test fun phoneSettingsDarkEs() = open(412, 915, "es", dark = true) { shoot("phone-settings-dark-es") }

    private fun androidx.compose.ui.test.ComposeUiTest.shoot(name: String) {
        waitUntil(timeoutMillis = 30_000) { onAllNodesWithText("Todo se guarda al momento.").fetchSemanticsNodes().isNotEmpty() }
        waitForIdle()
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "$name.png"))
    }
}
