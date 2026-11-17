package io.github.roberbarras.racha.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import io.github.roberbarras.racha.ui.app.RachaDependencies
import io.github.roberbarras.racha.ui.category.CategoryScreen
import io.github.roberbarras.racha.ui.design.Racha
import io.github.roberbarras.racha.ui.design.RachaTheme
import io.github.roberbarras.racha.ui.lists.HistoryScreen
import io.github.roberbarras.racha.ui.lists.MyListScreen
import io.github.roberbarras.racha.ui.search.SearchScreen
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * The remaining screens (spec §10.3 deliverables), each rendered on its own: their data comes from database threads,
 * and reaching them through navigation makes that timing unreliable under the test harness's virtual clock.
 */
@OptIn(ExperimentalTestApi::class)
class MoreScreensTest {
    private val originalLocale = Locale.getDefault()
    private val out = File(System.getProperty("racha.screenshots") ?: "build/screenshots").apply { mkdirs() }

    @AfterTest
    fun restoreLocale() = Locale.setDefault(originalLocale)

    private fun shoot(
        name: String?,
        width: Int,
        height: Int,
        waitFor: String,
        count: Int = 1,
        type: String? = null,
        screen: @Composable (RachaDependencies) -> Unit,
    ) = runDesktopComposeUiTest(width, height) {
        Locale.setDefault(Locale.forLanguageTag("es"))
        val deps = DemoLibrary.dependencies()
        setContent {
            val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                RachaTheme(dark = false) { Box(Modifier.fillMaxSize().background(Racha.colors.background)) { screen(deps) } }
            }
        }
        if (type != null) {
            waitUntil(timeoutMillis = 30_000) { onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
            onAllNodes(hasSetTextAction())[0].performTextInput(type)
        }
        try {
            waitUntil(timeoutMillis = 30_000) { onAllNodesWithText(waitFor, substring = true).fetchSemanticsNodes().size >= count }
        } catch (e: Throwable) {
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "FAILED-${name ?: "screen"}.png"))
            println("DUMP " + onRoot().printToString(maxDepth = 30).lines().filter { "Text" in it }.joinToString(" | ").take(3000))
            throw e
        }
        if (name != null) ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "$name.png"))
    }

    @Test fun desktopSearchEs() = shoot("desktop-search-es", 1200, 800, "LECCIONES Y EPISODIOS", type = "iam") { SearchScreen(it) {} }
    @Test fun phoneSearchEs() = shoot("phone-search-es", 412, 915, "LECCIONES Y EPISODIOS", type = "iam") { SearchScreen(it) {} }
    @Test fun desktopMyListEs() = shoot("desktop-mylist-es", 1200, 800, "Tu lista está vacía") { MyListScreen(it) {} }
    @Test fun desktopCategoryEs() = shoot("desktop-category-es", 1200, 800, "AWS Certified") { CategoryScreen(it, "courses", {}) {} }

    /** History lists what was watched, grouped by day (a lazy list: only what fits is composed, so "at least 3"). */
    @Test fun historyListsWhatWasWatched() = shoot("desktop-history-es", 1200, 800, "IAM Introduction", count = 3) { HistoryScreen(it) {} }
}
