package io.github.roberbarras.racha.android.resource

import android.graphics.Color
import android.webkit.WebView
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** ADR-0003 and spec §5 on the device: the course WebView's isolation and the platform PDF renderer. */
@RunWith(AndroidJUnit4::class)
class ResourceAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val assets = instrumentation.context.assets

    /** Copies an asset folder (the web test course) into app storage. */
    private fun copyTree(asset: String, target: File) {
        val children = assets.list(asset).orEmpty()
        if (children.isEmpty()) {
            target.parentFile!!.mkdirs()
            assets.open(asset).use { input -> target.outputStream().use { input.copyTo(it) } }
        } else {
            children.forEach { copyTree("$asset/$it", File(target, it)) }
        }
    }

    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    @Test
    fun coursePagesWorkAndCannotLeaveTheirCourse() {
        val base = File(context.filesDir, "web-test").apply { deleteRecursively(); mkdirs() }
        copyTree("AWS Course", File(base, "AWS Course"))
        copyTree("Other Course", File(base, "Other Course"))
        val external = mutableListOf<String>()
        val view = main {
            courseWebView(context, File(base, "AWS Course").path, onExternalLink = { external += it }).also {
                it.loadUrl(coursePageUrl(io.github.roberbarras.racha.domain.library.RelativePath.of("01 - Introduction/003 AWS Account Activation Troubleshooting.html")))
            }
        }
        val results = poll(view)
        val checks = results.lines().filter(String::isNotBlank).associate { it.substringBefore('=') to it.substringAfter('=') }
        assertEquals(
            setOf("javascript", "relativeCss", "relativeImage", "quizInteraction", "sameCourseFile", "fileUrlRead", "encodedTraversal", "siblingCourse", "absoluteEscape"),
            checks.keys,
            results,
        )
        listOf("javascript", "relativeCss", "relativeImage", "quizInteraction", "sameCourseFile").forEach { assertEquals("ok", checks[it], "$it in\n$results") }
        listOf("fileUrlRead", "encodedTraversal", "siblingCourse", "absoluteEscape").forEach { assertTrue(checks[it]!!.startsWith("blocked"), "$it leaked in\n$results") }

        // Following the page's external link opens the system browser instead of navigating inside the viewer.
        main { view.evaluateJavascript("document.querySelector('a').click()", null) }
        val deadline = System.currentTimeMillis() + 5_000
        while (external.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(100)
        assertEquals(listOf("https://aws.amazon.com/"), external)
        main { view.destroy() }
    }

    private fun poll(view: WebView): String {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            var value = ""
            val latch = CountDownLatch(1)
            main {
                view.evaluateJavascript("(function(){var r=document.getElementById('results');return r&&r.dataset.done==='true'?r.innerText:''})()") {
                    value = it.trim('"').replace("\\n", "\n"); latch.countDown()
                }
            }
            latch.await(2, TimeUnit.SECONDS)
            if (value.isNotEmpty()) return value
            Thread.sleep(200)
        }
        error("the page did not finish its checks")
    }

    @Test
    fun pdfPagesRenderAtTheRequestedWidth() = runBlocking {
        val file = File(context.filesDir, "sample.pdf").apply { assets.open("sample.pdf").use { input -> outputStream().use { input.copyTo(it) } } }
        AndroidPdfDocument.open(file.path)!!.use { pdf ->
            assertEquals(2, pdf.pageCount)
            assertTrue(abs(pdf.pageAspect(0) - 595f / 842f) < 0.01f)
            assertTrue(abs(pdf.pageAspect(1) - 842f / 595f) < 0.01f)
            val page = pdf.render(0, 600).asAndroidBitmap()
            assertEquals(600, page.width)
            // The blue box of page 1 (x 72..372, y 400..600 from the bottom) is drawn; the margin stays white.
            val scale = 600f / 595f
            val inside = page.getPixel((200 * scale).toInt(), page.height - (500 * scale).toInt())
            assertTrue(Color.blue(inside) > 150 && Color.red(inside) < 60, "box colour ${Integer.toHexString(inside)}")
            assertEquals(Color.WHITE, page.getPixel(5, 5))
        }
        assertEquals(null, AndroidPdfDocument.open(File(context.filesDir, "missing.pdf").path))
    }
}
