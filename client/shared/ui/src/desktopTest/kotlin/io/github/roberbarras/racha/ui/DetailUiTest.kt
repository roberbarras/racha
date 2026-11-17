package io.github.roberbarras.racha.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import io.github.roberbarras.racha.ui.design.Racha
import io.github.roberbarras.racha.ui.design.RachaTheme
import io.github.roberbarras.racha.ui.detail.DetailScreen
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test

/** A course's detail: a section can be marked watched and, once watched, unwatched again (H15). */
@OptIn(ExperimentalTestApi::class)
class DetailUiTest {
    private val originalLocale = Locale.getDefault()

    @AfterTest
    fun restoreLocale() = Locale.setDefault(originalLocale)

    @Test
    fun aSectionIsMarkedWatchedAndUnwatchedAgain() = runDesktopComposeUiTest(1440, 1200) {
        Locale.setDefault(Locale.ENGLISH)
        val deps = DemoLibrary.dependencies()
        setContent {
            val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                RachaTheme(dark = false) {
                    Box(Modifier.fillMaxSize().background(Racha.colors.background)) { DetailScreen(deps, DemoLibrary.aws.id, {}) { _, _ -> } }
                }
            }
        }
        waitUntil(timeoutMillis = 30_000) { onAllNodesWithText("Mark section as watched").fetchSemanticsNodes().isNotEmpty() }
        val unwatchedBefore = onAllNodesWithText("Mark section as unwatched").fetchSemanticsNodes().size

        onAllNodesWithText("Mark section as watched")[0].performClick()
        waitUntil(timeoutMillis = 30_000) { onAllNodesWithText("Mark section as unwatched").fetchSemanticsNodes().size == unwatchedBefore + 1 }

        onAllNodesWithText("Mark section as unwatched")[0].performClick()
        waitUntil(timeoutMillis = 30_000) { onAllNodesWithText("Mark section as unwatched").fetchSemanticsNodes().size == unwatchedBefore }
    }
}
