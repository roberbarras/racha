package io.github.roberbarras.racha.android

import android.graphics.Bitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.roberbarras.racha.domain.learning.Progress
import io.github.roberbarras.racha.domain.library.CategoryKey
import io.github.roberbarras.racha.domain.library.CategoryRoot
import io.github.roberbarras.racha.domain.library.ContentId
import io.github.roberbarras.racha.domain.library.RelativePath
import io.github.roberbarras.racha.domain.library.RootKey
import io.github.roberbarras.racha.files.NioLibrarySource
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.time.Clock

/**
 * Evidence for acceptance criterion 12: the real app (real database, real scan of a course in app storage) on the
 * device. Screenshots go to the app's external files dir; `adb pull` collects them. Run on a cleared app.
 */
@RunWith(AndroidJUnit4::class)
class AndroidUiScreenshotTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val assets = instrumentation.context.assets

    @Test
    fun home() {
        val graph = (context.applicationContext as RachaApplication).graph
        val course = File(context.filesDir, "library/AWS Certified Solutions Architect").apply { deleteRecursively(); mkdirs() }
        fun video(path: String, unique: String) = File(course, path).apply {
            parentFile!!.mkdirs()
            assets.open("sample.mp4").use { input -> outputStream().use { input.copyTo(it) } }
            appendText("\n#$unique")
        }
        fun text(path: String, content: String) = File(course, path).apply { parentFile!!.mkdirs(); writeText(content) }
        video("01 - Introduction/001 Course Introduction.mp4", "1")
        text("01 - Introduction/001 Course Introduction_es.srt", "1\n00:00:00,000 --> 00:00:01,000\nHola\n")
        video("01 - Introduction/002 Creating an AWS Account.mp4", "2")
        text("01 - Introduction/003 AWS Account Activation Troubleshooting.html", "<h1>Troubleshooting</h1>")
        text("02 - Code & Slides Download/001 Course-Material-Download.url", "[InternetShortcut]\nURL=https://example.com\n")
        video("04 - IAM - Identity and Access Management/001 IAM Introduction Users, Groups, Policies.mp4", "3")
        text("04 - IAM - Identity and Access Management/021 Identity and Access Management - Quiz.html", "<form>quiz</form>")
        val root = RootKey("0b4f1b5e-6d1f-4a35-9a51-6a3a1b0c9d10")
        runBlocking {
            graph.ensureBuiltInCategories()
            graph.database.categories.upsertRoot(CategoryRoot(root, CategoryKey.COURSES, NioLibrarySource.locationOf(course.toPath()), 0))
            graph.scanLibrary.category(CategoryKey.COURSES)
            val iam = ContentId.forVideo(CategoryKey.COURSES, root, RelativePath.of("04 - IAM - Identity and Access Management/001 IAM Introduction Users, Groups, Policies.mp4"))
            val now = Clock.System.now()
            graph.database.progress.save(Progress.start(iam, 2, now).recordPosition(1, null, now))
        }

        ActivityScenario.launch(MainActivity::class.java).use {
            Thread.sleep(5_000) // first frame, scan and posters
            save("android-home")
        }
    }

    private fun save(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val dir = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
