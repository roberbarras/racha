package io.github.roberbarras.racha.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import io.github.roberbarras.racha.ui.app.CategoryRoute
import io.github.roberbarras.racha.ui.app.MyListRoute
import io.github.roberbarras.racha.ui.app.RachaApp
import io.github.roberbarras.racha.ui.app.SearchRoute
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * Screenshots of the remaining screens (spec §10.3 deliverables), for design review and evidence. History is checked
 * on its own (deterministic); screens reached through navigation whose data comes from database threads are not
 * captured here, because the test harness's virtual clock makes that timing unreliable.
 */
@OptIn(ExperimentalTestApi::class)
class MoreScreensTest {
    private val originalLocale = Locale.getDefault()
    private val out = File(System.getProperty("racha.screenshots") ?: "build/screenshots").apply { mkdirs() }

    @AfterTest
    fun restoreLocale() = Locale.setDefault(originalLocale)

    private fun shoot(name: String, width: Int, height: Int, route: Any, waitFor: String, type: String? = null) = runDesktopComposeUiTest(width, height) {
        Locale.setDefault(Locale.forLanguageTag("es"))
        val deps = DemoLibrary.dependencies()
        var nav: NavHostController? = null
        setContent {
            val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
            val controller = rememberNavController().also { nav = it }
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) { RachaApp(deps, controller) }
        }
        waitUntil(timeoutMillis = 30_000) { onAllNodesWithText("12:34 / 45:10").fetchSemanticsNodes().isNotEmpty() }
        runOnIdle { nav!!.navigate(route) }
        if (type != null) {
            waitUntil(timeoutMillis = 30_000) { onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
            onAllNodes(hasSetTextAction())[0].performTextInput(type)
        }
        // A real pause first: these screens get their data from database threads, and polling the test's virtual
        // clock in a tight loop can keep re-checking before the result reaches the UI (a harness effect, not the app).
        Thread.sleep(3_000)
        waitForIdle()
        check(onAllNodesWithText(waitFor, substring = true).fetchSemanticsNodes().isNotEmpty()) { "\"$waitFor\" did not appear" }
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "$name.png"))
    }

    @Test fun desktopSearchEs() = shoot("desktop-search-es", 1440, 900, SearchRoute, "LECCIONES Y EPISODIOS", type = "iam")
    @Test fun desktopMyListEs() = shoot("desktop-mylist-es", 1440, 900, MyListRoute, "Mi lista")
    @Test fun desktopCategoryEs() = shoot("desktop-category-es", 1440, 900, CategoryRoute("courses"), "AWS Certified")
    @Test fun phoneSearchEs() = shoot("phone-search-es", 412, 915, SearchRoute, "LECCIONES Y EPISODIOS", type = "iam")

    /** History lists what was watched, by day (deterministic: the screen alone, no navigation). */
    @Test
    fun historyListsWhatWasWatched() = runDesktopComposeUiTest(1440, 900) {
        Locale.setDefault(Locale.forLanguageTag("es"))
        val deps = DemoLibrary.dependencies()
        setContent { io.github.roberbarras.racha.ui.design.RachaTheme(dark = false) { io.github.roberbarras.racha.ui.lists.HistoryScreen(deps) {} } }
        waitUntil(timeoutMillis = 30_000) { onAllNodesWithText("IAM Introduction", substring = true).fetchSemanticsNodes().size == 7 }
        check(onAllNodesWithText("HOY").fetchSemanticsNodes().isNotEmpty() || onAllNodesWithText("AYER").fetchSemanticsNodes().isNotEmpty())
    }
}
