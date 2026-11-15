package io.github.roberbarras.racha.android.resource

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.roberbarras.racha.android.MainActivity
import io.github.roberbarras.racha.android.RachaApplication
import io.github.roberbarras.racha.domain.library.CategoryKey
import io.github.roberbarras.racha.domain.library.CategoryRoot
import io.github.roberbarras.racha.domain.library.ContentId
import io.github.roberbarras.racha.domain.library.RelativePath
import io.github.roberbarras.racha.domain.library.RootKey
import io.github.roberbarras.racha.files.NioLibrarySource
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertTrue

/** Criterion 6 in the real app: a course's HTML page opens as an element, in the viewer, and counts as viewed. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ResourceUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app = context.applicationContext as RachaApplication
    private val root = RootKey("8a2b6c1d-4e5f-4a7b-9c0d-1e2f3a4b5c6d")
    private val pagePath = "Web Course/01 - Introduction/003 AWS Account Activation Troubleshooting.html"
    private val page = ContentId.forResource(CategoryKey.COURSES, root, RelativePath.of(pagePath))

    init {
        val assets = instrumentation.context.assets
        val library = File(context.filesDir, "resource-library").apply { deleteRecursively(); mkdirs() }
        fun copy(asset: String, target: File) {
            val children = assets.list(asset).orEmpty()
            if (children.isEmpty()) { target.parentFile!!.mkdirs(); assets.open(asset).use { i -> target.outputStream().use { i.copyTo(it) } } }
            else children.forEach { copy("$asset/$it", File(target, it)) }
        }
        copy("AWS Course", File(library, "Web Course"))
        File(library, "Web Course/01 - Introduction/001 Welcome.mp4").also { f -> assets.open("sample.mp4").use { i -> f.outputStream().use { i.copyTo(it) } } }
        runBlocking {
            app.graph.ensureBuiltInCategories()
            app.graph.database.categories.upsertRoot(CategoryRoot(root, CategoryKey.COURSES, NioLibrarySource.locationOf(library.toPath()), 0))
            app.graph.scanLibrary.category(CategoryKey.COURSES)
        }
    }

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private fun webView(): WebView? {
        fun find(view: View): WebView? = when (view) {
            is WebView -> view
            is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) }
            else -> null
        }
        var result: WebView? = null
        instrumentation.runOnMainSync { result = find(compose.activity.window.decorView) }
        return result
    }

    @Test
    fun anHtmlElementOpensInTheViewerAndCountsAsViewed() {
        compose.waitUntil(timeoutMillis = 20_000) { compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes().isNotEmpty() }
        Thread.sleep(3_500) // launch animation
        // Other tests add courses too: the library may be below the fold of the home list.
        compose.waitUntil(timeoutMillis = 20_000) {
            runCatching { compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("Web Course")) }.isSuccess
        }
        compose.onAllNodesWithText("Web Course").onFirst().performClick()
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText("AWS Account Activation Troubleshooting").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("AWS Account Activation Troubleshooting").onFirst().performClick()

        compose.waitUntil(timeoutMillis = 15_000) { webView() != null }
        compose.waitUntil(timeoutMillis = 20_000) { pageFinished() }
        compose.waitForIdle() // lets the screen transition (driven by the test clock) finish
        save("android-resource-html")

        compose.onNode(hasContentDescription("Back")).performClick()
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 10_000) {
            runBlocking { app.graph.database.progress.get(page)?.completed == true }
        }
        assertTrue(runBlocking { app.graph.database.progress.get(page)!!.completed }, "closing the viewer marks it viewed")
    }

    private fun pageFinished(): Boolean {
        val view = webView() ?: return false
        var done = false
        val latch = java.util.concurrent.CountDownLatch(1)
        instrumentation.runOnMainSync {
            view.evaluateJavascript("document.getElementById('results') && document.getElementById('results').dataset.done") { done = it == "\"true\""; latch.countDown() }
        }
        latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
        return done
    }

    private fun save(name: String) {
        val bitmap: Bitmap = instrumentation.uiAutomation.takeScreenshot()
        val dir = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
