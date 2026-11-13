package io.github.roberbarras.racha.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import io.github.roberbarras.racha.ui.app.ItemRoute
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.toAwtImage
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import io.github.roberbarras.racha.domain.preferences.Settings
import io.github.roberbarras.racha.domain.preferences.ThemeMode
import io.github.roberbarras.racha.ui.app.RachaApp
import io.github.roberbarras.racha.ui.app.RachaDependencies
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class HomeUiTest {
    private val originalLocale = Locale.getDefault()

    @AfterTest
    fun restoreLocale() = Locale.setDefault(originalLocale)

    @Composable
    private fun App(deps: RachaDependencies) {
        val owner = object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
        CompositionLocalProvider(LocalViewModelStoreOwner provides owner) { RachaApp(deps) }
    }

    @Test
    fun criterion2ContinueWatchingShowsTheLastElementWithItsTimestampAndThePrimaryTabIsOpen() = runDesktopComposeUiTest(1440, 900) {
        Locale.setDefault(Locale.ENGLISH)
        setContent { App(DemoLibrary.dependencies()) }
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("12:34 / 45:10").fetchSemanticsNodes().isNotEmpty() }

        onAllNodesWithText(DemoLibrary.awsIamLesson.title).fetchSemanticsNodes().isNotEmpty().let { check(it) }
        onNodeWithText("Up next").assertExists()
        onNode(hasText("Courses") and isSelected()).assertExists()
    }

    @Test
    fun criterion11LeisureModeShowsOnlyLeisureCategories() = runDesktopComposeUiTest(1440, 900) {
        Locale.setDefault(Locale.ENGLISH)
        setContent { App(DemoLibrary.dependencies()) }
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("12:34 / 45:10").fetchSemanticsNodes().isNotEmpty() }

        onNodeWithText("Leisure").performClick()
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(DemoLibrary.seriesEpisode.title).fetchSemanticsNodes().isNotEmpty() }

        check(onAllNodesWithText("12:34 / 45:10").fetchSemanticsNodes().isEmpty()) { "study content hidden in leisure" }
        onNode(hasText("Series") and isSelected()).assertExists()
    }

    @Test
    fun criterion10SpanishInterface() = runDesktopComposeUiTest(1440, 900) {
        Locale.setDefault(Locale.forLanguageTag("es"))
        setContent { App(DemoLibrary.dependencies()) }
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("CONTINUAR VIENDO").fetchSemanticsNodes().isNotEmpty() }

        onNodeWithText("HOY TOCA").assertExists()
        onNode(hasText("Cursos") and isSelected()).assertExists()
        onNodeWithText("Racha de 7 días").assertExists()
    }
}

/** Writes PNG evidence of the main screens (headless rendering, no display involved). */
@OptIn(ExperimentalTestApi::class)
class ScreenshotTest {
    private val out = File(System.getProperty("racha.screenshots") ?: "build/screenshots").apply { mkdirs() }

    private fun SemanticsNodeInteraction.save(name: String) {
        ImageIO.write(captureToImage().toAwtImage(), "png", File(out, "$name.png"))
    }

    private fun shoot(name: String, width: Int, height: Int, language: String, dark: Boolean, openCourse: Boolean = false) =
        runDesktopComposeUiTest(width, height) {
            Locale.setDefault(Locale.forLanguageTag(language))
            val deps = DemoLibrary.dependencies()
            runBlocking { deps.preferences.set(Settings.Theme, if (dark) ThemeMode.DARK else ThemeMode.LIGHT) }
            var navigation: NavHostController? = null
            setContent {
                val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
                val nav = rememberNavController().also { navigation = it }
                CompositionLocalProvider(LocalViewModelStoreOwner provides owner) { RachaApp(deps, nav) }
            }
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("12:34 / 45:10").fetchSemanticsNodes().isNotEmpty() }
            if (openCourse) {
                runOnIdle { navigation!!.navigate(ItemRoute(DemoLibrary.aws.id.value)) }
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Course Introduction").fetchSemanticsNodes().isNotEmpty() }
            }
            waitForIdle()
            onRoot().save(name)
        }


    @Test fun desktopHomeEs() = shoot("desktop-home-es", 1440, 900, "es", dark = false)
    @Test fun desktopHomeDarkEn() = shoot("desktop-home-dark-en", 1440, 900, "en", dark = true)
    @Test fun desktopDetailEs() = shoot("desktop-detail-es", 1440, 900, "es", dark = false, openCourse = true)
    @Test fun tabletHomeEs() = shoot("tablet-home-es", 1024, 768, "es", dark = false)
    @Test fun phoneHomeEs() = shoot("phone-home-es", 412, 915, "es", dark = false)
    @Test fun phoneDetailDarkEs() = shoot("phone-detail-dark-es", 412, 915, "es", dark = true, openCourse = true)
}

/** Frames of the cold-start animation, rendered with a manually advanced clock. */
@OptIn(ExperimentalTestApi::class)
class LaunchAnimationTest {
    private val out = File(System.getProperty("racha.screenshots") ?: "build/screenshots").apply { mkdirs() }

    @Test
    fun framesAndCompletion() = runDesktopComposeUiTest(412, 915) {
        var finished = false
        mainClock.autoAdvance = false
        setContent {
            io.github.roberbarras.racha.ui.design.RachaTheme(dark = false, reduceMotion = false) {
                io.github.roberbarras.racha.ui.app.LaunchAnimation(contentReady = true, onFinished = { finished = true })
            }
        }
        listOf(0L, 600L, 1200L).forEach { ms ->
            mainClock.advanceTimeBy(if (ms == 0L) 16 else 600)
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "launch-${ms}ms.png"))
        }
        mainClock.advanceTimeBy(1_500)
        waitForIdle()
        check(finished) { "the overlay leaves after about 2 s when content is ready" }
    }
}
