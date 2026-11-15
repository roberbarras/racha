package io.github.roberbarras.racha.ui

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
        onNodeWithText("TIME BY CATEGORY").assertExists() // section headers are shown in capitals
        onAllNodesWithText("Courses").assertCountEquals(2) // the sidebar category and the bar
        onNodeWithText("Series").assertExists()
    }
}
