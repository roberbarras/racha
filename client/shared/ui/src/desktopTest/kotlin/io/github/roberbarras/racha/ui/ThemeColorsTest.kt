package io.github.roberbarras.racha.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.compose.rememberNavController
import io.github.roberbarras.racha.domain.preferences.Settings
import io.github.roberbarras.racha.domain.preferences.ThemeMode
import io.github.roberbarras.racha.ui.app.RachaApp
import io.github.roberbarras.racha.ui.design.Racha
import io.github.roberbarras.racha.ui.design.RachaTheme
import io.github.roberbarras.racha.ui.design.ThemeColors
import io.github.roberbarras.racha.ui.design.Tokens
import io.github.roberbarras.racha.ui.settings.SettingsScreen
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The user's theme colours (Settings → General): any colour keeps text readable; chosen ones apply at once. */
@OptIn(ExperimentalTestApi::class)
class ThemeColorsTest {
    private val originalLocale = Locale.getDefault()
    private val out = File(System.getProperty("racha.screenshots") ?: "build/screenshots").apply { mkdirs() }

    @AfterTest
    fun restoreLocale() = Locale.setDefault(originalLocale)

    private val extremes = listOf(
        Color.White, Color.Black, Color(0xFF00FF00), Color(0xFFFF0000), Color(0xFF0000FF), Color(0xFFFFFF00),
        Color(0xFF00FFFF), Color(0xFFFF00FF), Color(0xFF808080), Color(0xFF7B61FF), Color(0xFFF5E1E8), Color(0xFF14301F),
    )

    @Test
    fun anyColourKeepsTextReadableInLightAndDark() {
        for (dark in listOf(false, true)) {
            val base = if (dark) Tokens.Dark else Tokens.Light
            for (c in extremes) {
                val p = ThemeColors.palette(base, dark, accent = c, background = c, rail = c)
                assertTrue(ThemeColors.contrast(p.ink, p.background) >= 7f, "text on background $c dark=$dark")
                assertTrue(ThemeColors.contrast(p.ink, p.surface) >= 7f, "text on surface $c dark=$dark")
                assertTrue(ThemeColors.contrast(p.inkMuted, p.surface) >= 4.5f, "muted text on surface $c dark=$dark")
                assertTrue(ThemeColors.contrast(p.onRail, p.rail) >= 7f, "text on bars $c dark=$dark")
                assertTrue(ThemeColors.contrast(p.onAccent, p.accent) >= 4.5f, "text on accent $c dark=$dark")
                assertEquals(c, p.accent, "the accent is exactly the colour chosen")
            }
            assertEquals(base, ThemeColors.palette(base, dark, null, null, null), "nothing chosen: Racha's palette as it is")
        }
    }

    @Test
    fun swatchesAndATypedColourAreSaved() = runDesktopComposeUiTest(1200, 2200) {
        Locale.setDefault(Locale.ENGLISH)
        val deps = DemoLibrary.dependencies()
        setContent {
            val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                RachaTheme(dark = false) { Box(Modifier.fillMaxSize().background(Racha.colors.background)) { SettingsScreen(deps, onFolders = {}, onAccount = null) } }
            }
        }
        waitUntil(timeoutMillis = 15_000) { onAllNodesWithText("Background tone").fetchSemanticsNodes().isNotEmpty() }
        onNode(hasContentDescription("#DFEFE2")).performClick()
        onNode(hasContentDescription("#2A1B45")).performClick()
        // Each choice is saved on its own: wait for both.
        waitUntil(timeoutMillis = 5_000) { runBlocking { deps.preferences.get(Settings.BackgroundColor) } == "#DFEFE2" }
        waitUntil(timeoutMillis = 5_000) { runBlocking { deps.preferences.get(Settings.RailColor) } == "#2A1B45" }

        // Any colour, typed: on the accent row (the first "Another colour").
        onAllNodesWithContentDescriptionFirst("Another colour").performClick()
        // It opens with the current colour: replace it.
        onNode(hasContentDescription("Colour (#RRGGBB)")).performTextReplacement("#0A84FF")
        onNodeWithText("Apply").performClick()
        waitUntil(timeoutMillis = 5_000) { runBlocking { deps.preferences.get(Settings.AccentColor) } == "#0A84FF" }

        onNodeWithText("Restore Racha's colours").performClick()
        waitUntil(timeoutMillis = 5_000) {
            runBlocking {
                deps.preferences.get(Settings.BackgroundColor).isEmpty() && deps.preferences.get(Settings.RailColor).isEmpty() &&
                    deps.preferences.get(Settings.AccentColor) == Settings.AccentColor.default
            }
        }
    }

    private fun androidx.compose.ui.test.ComposeUiTest.onAllNodesWithContentDescriptionFirst(text: String) =
        onAllNodes(hasContentDescription(text))[0]

    private fun shoot(name: String, dark: Boolean) = runDesktopComposeUiTest(1440, 900) {
        Locale.setDefault(Locale.forLanguageTag("es"))
        val deps = DemoLibrary.dependencies()
        runBlocking {
            deps.preferences.set(Settings.Theme, if (dark) ThemeMode.DARK else ThemeMode.LIGHT)
            deps.preferences.set(Settings.AccentColor, "#2A9D8F")
            deps.preferences.set(Settings.BackgroundColor, "#DCE7F6")
            deps.preferences.set(Settings.RailColor, "#2A1B45")
        }
        setContent {
            val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
            val nav = rememberNavController()
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) { RachaApp(deps, nav) }
        }
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("12:34 / 45:10").fetchSemanticsNodes().isNotEmpty() }
        waitForIdle()
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "$name.png"))
    }

    @Test fun customColoursLight() = shoot("desktop-home-custom-colours-light", dark = false)
    @Test fun customColoursDark() = shoot("desktop-home-custom-colours-dark", dark = true)
}
