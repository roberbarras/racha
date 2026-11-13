package io.github.roberbarras.racha.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.roberbarras.racha.android.library.AndroidMediaProbe
import io.github.roberbarras.racha.android.library.AndroidThumbnailRenderer
import io.github.roberbarras.racha.android.library.FileObserverLibraryWatcher
import io.github.roberbarras.racha.application.library.EnsureBuiltInCategories
import io.github.roberbarras.racha.application.library.scan.HashEngagedElements
import io.github.roberbarras.racha.application.library.scan.RefreshThumbnails
import io.github.roberbarras.racha.application.library.scan.ScanLibrary
import io.github.roberbarras.racha.application.preferences.Preferences
import io.github.roberbarras.racha.application.shared.Clock
import io.github.roberbarras.racha.domain.learning.Progress
import io.github.roberbarras.racha.domain.library.CategoryKey
import io.github.roberbarras.racha.domain.library.CategoryRoot
import io.github.roberbarras.racha.domain.library.ContentId
import io.github.roberbarras.racha.domain.library.ElementKind
import io.github.roberbarras.racha.domain.library.LanguageCode
import io.github.roberbarras.racha.domain.library.RelativePath
import io.github.roberbarras.racha.domain.library.RootKey
import io.github.roberbarras.racha.files.NioLibrarySource
import io.github.roberbarras.racha.files.Sha256FileHasher
import io.github.roberbarras.racha.files.SystemLocalDates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock as SystemClock

/** Acceptance criteria 1 and 7 on the device: java.nio listing, platform demuxers, framework SQLite. */
@RunWith(AndroidJUnit4::class)
class AndroidScanTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val testAssets = InstrumentationRegistry.getInstrumentation().context.assets
    private val courseDir = File(context.filesDir, "scan-test/AWS Certified Solutions Architect").apply { deleteRecursively(); mkdirs() }
    private val db = AndroidDatabase.create(context, name = null)
    private val preferences = Preferences(db.settings, Clock.SYSTEM)
    private val rootKey = RootKey("0b4f1b5e-6d1f-4a35-9a51-6a3a1b0c9d10")
    private val location = NioLibrarySource.locationOf(courseDir.toPath())
    private val scan = ScanLibrary(
        db.categories, db.library, db.progress, NioLibrarySource(), AndroidMediaProbe(), Sha256FileHasher(), preferences,
        Clock.SYSTEM, SystemLocalDates(),
    )

    private fun video(path: String, asset: String, unique: String) = File(courseDir, path).apply {
        parentFile!!.mkdirs()
        testAssets.open(asset).use { input -> outputStream().use { input.copyTo(it) } }
        appendText("\n#$unique")
    }

    private fun text(path: String, content: String) = File(courseDir, path).apply { parentFile!!.mkdirs(); writeText(content) }

    private fun writeSpecExample() {
        video("01 - Introduction/001 Course Introduction.mp4", "sample.mp4", "1")
        text("01 - Introduction/001 Course Introduction_es.srt", "1\n00:00:00,000 --> 00:00:01,000\nHola\n")
        video("01 - Introduction/002 Creating an AWS Account.mp4", "sample.mp4", "2")
        text("01 - Introduction/002 Creating an AWS Account_es.srt", "1\n00:00:00,000 --> 00:00:01,000\nCuenta\n")
        text("01 - Introduction/003 AWS Account Activation Troubleshooting.html", "<h1>Troubleshooting</h1>")
        text("02 - Code & Slides Download/001 Code & Slides Download.html", "<a>code</a>")
        text("02 - Code & Slides Download/001 Course-Material-Download.url", "[InternetShortcut]\nURL=https://example.com\n")
        text("02 - Code & Slides Download/external-links.txt", "https://aws.amazon.com\n")
        video("04 - IAM - Identity and Access Management/001 IAM Introduction Users, Groups, Policies.mp4", "sample.mp4", "3")
        text("04 - IAM - Identity and Access Management/001 IAM Introduction Users, Groups, Policies_es.srt", "1\n00:00:00,000 --> 00:00:01,000\nIAM\n")
        text("04 - IAM - Identity and Access Management/021 Identity and Access Management - Quiz.html", "<form>quiz</form>")
        text("temp/index_XXXXX_1920x1080.m3u8", "#EXTM3U\n")
        text("temp/index_XXXXX_1920x1080_00001.ts", "segment")
    }

    private suspend fun setUpCategory() {
        EnsureBuiltInCategories(db.categories, Clock.SYSTEM)()
        db.categories.upsertRoot(CategoryRoot(rootKey, CategoryKey.COURSES, location, 0))
    }

    private suspend fun course() = db.library.itemsInRoot(CategoryKey.COURSES, rootKey).single { !it.archived }

    private fun videoId(path: String) = ContentId.forVideo(CategoryKey.COURSES, rootKey, RelativePath.of(path))

    @Test
    fun criterion1SpecExampleOnTheDevice() = runBlocking {
        writeSpecExample()
        setUpCategory()

        scan.all()

        val course = course()
        assertEquals(listOf("Introduction", "Code & Slides Download", "IAM - Identity and Access Management"), db.library.sections(course.id).map { it.title })
        val elements = db.library.elements(course.id)
        assertEquals(8, elements.size)
        assertTrue(elements.none { it.relativePath.value.startsWith("temp/") })
        val videos = elements.filter { it.kind == ElementKind.VIDEO }
        videos.forEach { video ->
            assertEquals(listOf(LanguageCode("es")), db.library.subtitles(video.id).map { it.language })
            assertEquals(2L, video.media.durationSec)
            assertEquals(160 to 90, video.media.width to video.media.height)
            assertEquals("h264", video.media.videoCodec)
        }
    }

    @Test
    fun criterion7OnTheDevice() = runBlocking {
        writeSpecExample()
        setUpCategory()
        scan.all()
        val watched = "01 - Introduction/002 Creating an AWS Account.mp4"
        val now = SystemClock.System.now()
        db.progress.save(Progress.start(videoId(watched), 2, now).recordPosition(1, null, now))
        HashEngagedElements(db.categories, db.library, Sha256FileHasher())()

        val moved = "04 - IAM - Identity and Access Management/002 Creating an AWS Account.mp4"
        assertTrue(File(courseDir, watched).renameTo(File(courseDir, moved)))
        assertTrue(File(courseDir, "01 - Introduction/001 Course Introduction.mp4").delete())
        val report = scan.all()

        assertEquals(1, report.relinked)
        assertEquals(1L, db.progress.get(videoId(moved))?.positionSec)
        assertTrue(assertNotNull(db.library.element(videoId("01 - Introduction/001 Course Introduction.mp4"))).archived)
    }

    @Test
    fun postersAndWatcherWorkOnTheDevice() = runBlocking {
        writeSpecExample()
        setUpCategory()
        scan.all()

        val renderer = AndroidThumbnailRenderer(File(context.cacheDir, "thumbs-test").apply { deleteRecursively() })
        assertEquals(1, RefreshThumbnails(db.categories, db.library, renderer, preferences)())
        val poster = File(java.net.URI(course().posterPath!!))
        assertEquals("RIFF", poster.inputStream().use { String(it.readNBytes(4)) })

        val change = async(Dispatchers.Default) { withTimeout(10_000) { FileObserverLibraryWatcher().changes(location).first() } }
        delay(500)
        text("04 - IAM - Identity and Access Management/022 New lesson.html", "<p>new</p>")
        change.await()
    }
}
