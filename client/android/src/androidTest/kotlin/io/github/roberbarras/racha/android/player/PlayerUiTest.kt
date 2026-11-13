package io.github.roberbarras.racha.android.player

import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.roberbarras.racha.android.MainActivity
import io.github.roberbarras.racha.android.RachaApplication
import io.github.roberbarras.racha.application.playback.EngineSnapshot
import io.github.roberbarras.racha.domain.learning.Progress
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
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Spec §4.2 on the device through the real app: resume from "Continue watching", subtitles by language, speed, A-B
 * loop, frame capture, picture-in-picture and the save on leaving. Screenshots go to the app's external files dir.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class PlayerUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app = context.applicationContext as RachaApplication
    private val graph = app.graph
    private val root = RootKey("6f0c3a51-9d4e-4f8e-8f0e-3b7a2c1d5e90")
    private val lessonPath = "01 - Introduction/001 Kotlin Basics.mp4"
    private val lesson = ContentId.forVideo(CategoryKey.COURSES, root, RelativePath.of(lessonPath))

    init {
        seed()
    }

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private fun seed() {
        val assets = instrumentation.context.assets
        val course = File(context.filesDir, "player-library/Kotlin Course").apply { deleteRecursively(); mkdirs() }
        fun copy(path: String, unique: String) = File(course, path).apply {
            parentFile!!.mkdirs()
            assets.open("long.mp4").use { input -> outputStream().use { input.copyTo(it) } }
            appendText("\n#$unique")
        }
        copy(lessonPath, "1")
        File(course, "01 - Introduction/001 Kotlin Basics_es.srt").writeText("1\n00:00:00,000 --> 00:00:30,000\nHola desde Racha\n")
        copy("01 - Introduction/002 Variables.mp4", "2")
        runBlocking {
            graph.ensureBuiltInCategories()
            graph.database.categories.upsertRoot(CategoryRoot(root, CategoryKey.COURSES, NioLibrarySource.locationOf(course.toPath()), 0))
            graph.scanLibrary.category(CategoryKey.COURSES)
            val now = Clock.System.now()
            graph.database.progress.save(Progress.start(lesson, 30, now).recordPosition(12, 30, now))
        }
    }

    private fun engine(): EngineSnapshot? = app.playerHooks.visibleEngines.firstOrNull()?.snapshot?.value

    private fun position(): Long = instrumentation.runOnMainSyncResult { app.playerHooks.visibleEngines.first().positionMs() }

    private fun <T> android.app.Instrumentation.runOnMainSyncResult(block: () -> T): T {
        var result: T? = null
        runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    /** Controls hide after 3 s of playback: a tap in the middle brings them back. */
    private fun withControls(action: () -> Unit) {
        if (compose.onAllNodes(hasContentDescription("Pause")).fetchSemanticsNodes().isEmpty()) {
            compose.onRoot().performTouchInput { click(center) }
            compose.waitUntil(timeoutMillis = 5_000) { compose.onAllNodes(hasContentDescription("Pause")).fetchSemanticsNodes().isNotEmpty() }
        }
        action()
    }

    private fun clickDescription(text: String) = withControls { compose.onNode(hasContentDescription(text)).performClick() }

    private fun openPlayerFromContinueWatching() {
        compose.waitUntil(timeoutMillis = 20_000) { compose.onAllNodesWithText("Kotlin Basics").fetchSemanticsNodes().isNotEmpty() }
        Thread.sleep(3_500) // the launch animation covers the home for ~2 s
        compose.onAllNodesWithText("Kotlin Basics").onFirst().performClick()
        compose.waitUntil(timeoutMillis = 15_000) { engine()?.playing == true }
    }

    @Test
    fun resumesAndHandlesSubtitlesSpeedLoopCaptureAndTheSaveOnLeaving() {
        openPlayerFromContinueWatching()
        assertTrue(position() >= 11_000, "resumed from 0:12, at ${position()}")

        clickDescription("Subtitles")
        compose.onNode(hasText("ES")).performClick()
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText("Hola desde Racha").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        save("android-player-subtitles")
        compose.onNode(hasContentDescription("Close")).performClick()

        withControls { compose.onNode(hasText("1×")).performClick() }
        compose.onNode(hasText("1.5×")).performClick()
        compose.waitUntil(timeoutMillis = 5_000) { engine()?.speed == 1.5f }
        compose.onNode(hasContentDescription("Close")).performClick()

        clickDescription("A-B loop")
        Thread.sleep(1_500)
        clickDescription("A-B loop")
        compose.waitUntil(timeoutMillis = 5_000) { compose.onAllNodes(hasText("Loop", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        withControls { save("android-player-controls") }

        clickDescription("Screenshot")
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodes(hasText("Screenshot saved to", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(capturedFrames() > 0, "the frame is in Pictures/Racha")

        clickDescription("Back")
        compose.waitUntil(timeoutMillis = 10_000) { app.playerHooks.visibleEngines.isEmpty() }
        val saved = runBlocking {
            var progress: Progress? = null
            repeat(20) { progress = graph.database.progress.get(lesson); if (progress!!.positionSec >= 13) return@runBlocking progress; Thread.sleep(250) }
            progress
        }
        assertNotNull(saved)
        assertTrue(saved.positionSec >= 13, "position saved on leaving: ${saved.positionSec}")
    }

    @Test
    fun entersPictureInPictureAndKeepsPlaying() {
        openPlayerFromContinueWatching()
        clickDescription("Picture in picture")
        compose.waitUntil(timeoutMillis = 10_000) { app.playerHooks.inPictureInPicture.value }
        Thread.sleep(1_500)
        assertEquals(true, engine()?.playing, "playback continues in the small window")
        save("android-player-pip")
    }

    private fun capturedFrames(): Int {
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?"
        context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, selection, arrayOf("Pictures/Racha%", "racha-%"), null)
            .use { return it?.count ?: 0 }
    }

    private fun save(name: String) {
        val bitmap: Bitmap = instrumentation.uiAutomation.takeScreenshot()
        val dir = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
