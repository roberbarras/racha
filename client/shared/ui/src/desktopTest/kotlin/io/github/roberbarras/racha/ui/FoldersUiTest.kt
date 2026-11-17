package io.github.roberbarras.racha.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.roberbarras.racha.domain.library.CategoryKey
import io.github.roberbarras.racha.domain.library.RootLayout
import io.github.roberbarras.racha.ui.app.AppViewModel
import io.github.roberbarras.racha.ui.app.RachaDependencies
import io.github.roberbarras.racha.ui.design.Racha
import io.github.roberbarras.racha.ui.design.RachaTheme
import io.github.roberbarras.racha.ui.folders.FoldersScreen
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Adding a folder as a library or as one course, seeing it first, and changing it later (ADR-0013). */
@OptIn(ExperimentalTestApi::class)
class FoldersUiTest {
    private val originalLocale = Locale.getDefault()
    private val out = File(System.getProperty("racha.screenshots") ?: "build/screenshots").apply { mkdirs() }
    private val picked = "file:///media/Python desde cero"

    /** A course whose sections have plain names: the old name-based guess made one course per section. */
    private val course = listOf(
        "Introducción/01 Bienvenida.mp4", "Introducción/02 Cómo usar el curso.mp4",
        "Fundamentos/01 Variables.mp4", "Fundamentos/02 Funciones.mp4", "Proyecto final/01 Enunciado.mp4",
    )

    @AfterTest
    fun restoreLocale() = Locale.setDefault(originalLocale)

    private fun ComposeUiTest.screen(deps: RachaDependencies) = setContent {
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
            RachaTheme(dark = false) {
                Box(Modifier.fillMaxSize().background(Racha.colors.background)) { FoldersScreen(deps, viewModel { AppViewModel(deps) }) }
            }
        }
    }

    private fun ComposeUiTest.waitOrDump(step: String, condition: () -> Boolean) = try {
        waitUntil(timeoutMillis = 30_000, condition = condition)
    } catch (e: Throwable) {
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "FAILED-folders-$step.png"))
        val texts = onRoot().printToString(maxDepth = 30).lines().filter { "Text" in it }.joinToString(" | ").take(2000)
        throw AssertionError("timed out waiting for $step; screen: $texts", e)
    }

    private fun ComposeUiTest.shows(text: String) = onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    /** Courses of the added folder that are not archived. */
    private fun courses(deps: RachaDependencies) = runBlocking {
        val root = deps.manageRoots.roots(CategoryKey.COURSES).singleOrNull() ?: return@runBlocking emptyList()
        DemoLibrary.database.library.itemsInRoot(CategoryKey.COURSES, root.key).filterNot { it.archived }
    }

    @Test
    fun aCourseFolderIsSeenBeforeAddingAndCanBeChangedLater() = runDesktopComposeUiTest(1200, 1000) {
        Locale.setDefault(Locale.forLanguageTag("es"))
        val deps = DemoLibrary.dependencies(empty = true, picked = picked, pickedFiles = course)
        screen(deps)
        waitOrDump("add buttons") { shows("Añadir curso o serie") }

        onAllNodesWithText("Añadir curso o serie")[0].performClick() // Cursos comes first
        waitOrDump("preview as one course") { shows("Se añadirá «Python desde cero»") }
        assertEquals(true, shows("3 secciones · 5 vídeos y recursos"))
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "desktop-folders-preview-es.png"))

        onAllNodesWithText("Son varios cursos o series")[0].performClick()
        waitOrDump("preview as a library") { shows("Se añadirán 3 cursos o series") }
        assertEquals(true, shows("Fundamentos · Introducción · Proyecto final"))
        onAllNodesWithText("Es un solo curso o serie")[0].performClick()
        waitOrDump("back to one course") { shows("Se añadirá «Python desde cero»") }

        onNode(hasText("Añadir") and hasClickAction()).performClick()
        waitOrDump("one course added") { courses(deps).map { it.title } == listOf("Python desde cero") }
        waitOrDump("its layout shown") { shows("/media/Python desde cero") && !shows("Se añadirá") }
        assertEquals(RootLayout.SINGLE_ITEM, runBlocking { deps.manageRoots.roots(CategoryKey.COURSES).single().layout })

        onNode(hasContentDescription("Cambiar qué contiene")).performClick()
        waitOrDump("layout chooser") { shows("¿Qué contiene esta carpeta?") }
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "desktop-folders-change-layout-es.png"))
        onAllNodesWithText("Varios cursos o series")[0].performClick()
        waitOrDump("now a library") { courses(deps).map { it.title }.sorted() == listOf("Fundamentos", "Introducción", "Proyecto final") }
        assertEquals(RootLayout.LIBRARY, runBlocking { deps.manageRoots.roots(CategoryKey.COURSES).single().layout })
    }
}
