package io.github.roberbarras.racha.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import io.github.roberbarras.racha.domain.playback.KeyCombo
import io.github.roberbarras.racha.domain.playback.PlayerAction
import io.github.roberbarras.racha.domain.playback.Shortcuts
import io.github.roberbarras.racha.ui.design.Racha
import io.github.roberbarras.racha.ui.design.RachaTheme
import io.github.roberbarras.racha.ui.player.ShortcutsEditor
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Settings → Player → Keyboard shortcuts (Linux, H15). */
@OptIn(ExperimentalTestApi::class)
class ShortcutsUiTest {
    private val originalLocale = Locale.getDefault()
    private val out = File(System.getProperty("racha.screenshots") ?: "build/screenshots").apply { mkdirs() }

    @AfterTest
    fun restoreLocale() = Locale.setDefault(originalLocale)

    @Test
    fun keysAreCapturedMovedBetweenActionsAndReset() = runDesktopComposeUiTest(1100, 1400) {
        Locale.setDefault(Locale.ENGLISH)
        var shortcuts by mutableStateOf(Shortcuts.DEFAULT)
        setContent {
            RachaTheme(dark = false) {
                Box(Modifier.fillMaxSize().background(Racha.colors.background).padding(24.dp).verticalScroll(rememberScrollState())) {
                    ShortcutsEditor(shortcuts) { shortcuts = it }
                }
            }
        }
        waitUntil(timeoutMillis = 30_000) { onAllNodesWithText("Mute").fetchSemanticsNodes().isNotEmpty() }

        // "Add" on Mute (the 6th action) and press Ctrl+M.
        onAllNodesWithText("Add")[PlayerAction.MUTE.ordinal].performClick()
        onNodeWithText("Press the combination… (Escape to cancel)").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.M) } }
        waitUntil(timeoutMillis = 10_000) { shortcuts.keysOf(PlayerAction.MUTE).size == 2 }
        assertEquals(listOf(KeyCombo("M"), KeyCombo("M", ctrl = true)), shortcuts.keysOf(PlayerAction.MUTE))

        // K belongs to play/pause: given to Next, it moves and the screen says from where.
        onAllNodesWithText("Add")[PlayerAction.NEXT.ordinal].performClick()
        onNodeWithText("Press the combination… (Escape to cancel)").performKeyInput { pressKey(Key.K) }
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Taken from \"Play / pause\".").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf(KeyCombo("Space")), shortcuts.keysOf(PlayerAction.PLAY_PAUSE))
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "desktop-shortcuts-en.png"))

        // Escape cancels a capture without assigning anything.
        onAllNodesWithText("Add")[PlayerAction.BOOKMARK.ordinal].performClick()
        onNodeWithText("Press the combination… (Escape to cancel)").performKeyInput { pressKey(Key.Escape) }
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Press the combination… (Escape to cancel)").fetchSemanticsNodes().isEmpty() }
        assertEquals(listOf(KeyCombo("B")), shortcuts.keysOf(PlayerAction.BOOKMARK))

        onNodeWithText("Reset all").performClick()
        waitUntil(timeoutMillis = 10_000) { shortcuts == Shortcuts.DEFAULT }
        assertEquals("", shortcuts.encode())
    }
}
