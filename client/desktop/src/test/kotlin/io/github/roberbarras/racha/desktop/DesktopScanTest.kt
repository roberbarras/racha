package io.github.roberbarras.racha.desktop

import io.github.roberbarras.racha.application.library.EnsureBuiltInCategories
import io.github.roberbarras.racha.application.library.scan.HashEngagedElements
import io.github.roberbarras.racha.application.library.scan.RefreshThumbnails
import io.github.roberbarras.racha.application.library.scan.ScanLibrary
import io.github.roberbarras.racha.application.preferences.Preferences
import io.github.roberbarras.racha.application.shared.Clock
import io.github.roberbarras.racha.data.LocalDatabase
import io.github.roberbarras.racha.desktop.library.MpvMediaProbe
import io.github.roberbarras.racha.desktop.library.MpvThumbnailRenderer
import io.github.roberbarras.racha.desktop.library.WatchServiceLibraryWatcher
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
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Acceptance criteria 1 and 7 on a real file system, with libmpv metadata and a file-backed SQLite database. */
class DesktopScanTest {
    private val work: Path = Files.createTempDirectory("racha-scan")
    private val courseDir: Path = work.resolve("AWS Certified Solutions Architect")
    private val db: LocalDatabase = DesktopDatabase.open(work.resolve("racha.db"))
    private val preferences = Preferences(db.settings, Clock.SYSTEM)
    private val probe = MpvMediaProbe()
    private val rootKey = RootKey("0b4f1b5e-6d1f-4a35-9a51-6a3a1b0c9d10")
    private val location = NioLibrarySource.locationOf(courseDir)
    private val scan = ScanLibrary(
        db.categories, db.library, db.progress, NioLibrarySource(), probe, Sha256FileHasher(), preferences, Clock.SYSTEM, SystemLocalDates(),
    )

    @AfterTest
    fun tearDown() = probe.close()

    private fun media(name: String): Path = Path.of("../testdata/media", name).toAbsolutePath()

    /** Writes the spec §3.1 tree; each video gets a unique tail so contents (and hashes) differ. */
    private fun writeSpecExample() {
        fun video(path: String, unique: String) {
            val target = courseDir.resolve(path).also { Files.createDirectories(it.parent) }
            Files.copy(media("sample.mp4"), target)
            Files.writeString(target, "\n#$unique", StandardOpenOption.APPEND)
        }
        fun text(path: String, content: String) =
            courseDir.resolve(path).also { Files.createDirectories(it.parent); Files.writeString(it, content) }
        video("01 - Introduction/001 Course Introduction.mp4", "1")
        text("01 - Introduction/001 Course Introduction_es.srt", "1\n00:00:00,000 --> 00:00:01,000\nHola\n")
        video("01 - Introduction/002 Creating an AWS Account.mp4", "2")
        text("01 - Introduction/002 Creating an AWS Account_es.srt", "1\n00:00:00,000 --> 00:00:01,000\nCuenta\n")
        text("01 - Introduction/003 AWS Account Activation Troubleshooting.html", "<h1>Troubleshooting</h1>")
        text("02 - Code & Slides Download/001 Code & Slides Download.html", "<a href='code.zip'>code</a>")
        text("02 - Code & Slides Download/001 Course-Material-Download.url", "[InternetShortcut]\nURL=https://example.com\n")
        text("02 - Code & Slides Download/external-links.txt", "https://aws.amazon.com\n")
        video("04 - IAM - Identity and Access Management/001 IAM Introduction Users, Groups, Policies.mp4", "3")
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
    fun criterion1SpecExampleOnDisk() = runBlocking {
        writeSpecExample()
        setUpCategory()

        scan.all()

        val course = course()
        assertEquals("AWS Certified Solutions Architect", course.title)
        assertEquals(listOf("Introduction", "Code & Slides Download", "IAM - Identity and Access Management"), db.library.sections(course.id).map { it.title })
        val elements = db.library.elements(course.id)
        assertEquals(8, elements.size)
        assertTrue(elements.none { it.relativePath.value.startsWith("temp/") }, "temp/ hidden by default")
        val videos = elements.filter { it.kind == ElementKind.VIDEO }
        assertEquals(listOf(1, 2, 1), videos.map { it.order })
        videos.forEach { video ->
            assertEquals(listOf(LanguageCode("es")), db.library.subtitles(video.id).map { it.language })
            assertEquals(2L, video.media.durationSec)
            assertEquals(160 to 90, video.media.width to video.media.height)
            assertEquals("h264", video.media.videoCodec)
        }
        assertEquals(6L, course.totalDurationSec)
    }

    @Test
    fun criterion7MovedFilesKeepProgressAndDeletedFilesStayArchived() = runBlocking {
        writeSpecExample()
        setUpCategory()
        scan.all()
        val watchedPath = "01 - Introduction/002 Creating an AWS Account.mp4"
        db.progress.save(Progress.start(videoId(watchedPath), 2, kotlin.time.Clock.System.now()).recordPosition(1, null, kotlin.time.Clock.System.now()))
        HashEngagedElements(db.categories, db.library, Sha256FileHasher())()

        val movedPath = "04 - IAM - Identity and Access Management/002 Creating an AWS Account.mp4"
        Files.move(courseDir.resolve(watchedPath), courseDir.resolve(movedPath))
        Files.delete(courseDir.resolve("01 - Introduction/001 Course Introduction.mp4"))
        val report = scan.all()

        assertEquals(1, report.relinked)
        assertEquals(1L, db.progress.get(videoId(movedPath))?.positionSec, "progress followed the moved file")
        val deleted = assertNotNull(db.library.element(videoId("01 - Introduction/001 Course Introduction.mp4")), "deleted file stays in the app")
        assertTrue(deleted.archived)
    }

    @Test
    fun postersAreRenderedFromTheFirstVideo() = runBlocking {
        writeSpecExample()
        setUpCategory()
        scan.all()

        val rendered = RefreshThumbnails(db.categories, db.library, MpvThumbnailRenderer(work.resolve("thumbs")), preferences)()

        assertEquals(1, rendered)
        val poster = Path.of(URI(course().posterPath!!))
        assertTrue(Files.size(poster) > 0)
        assertEquals("RIFF", Files.newInputStream(poster).use { String(it.readNBytes(4)) }, "WebP file")
    }

    @Test
    fun watcherReportsNewFiles() = runBlocking {
        writeSpecExample()
        val firstChange = async(Dispatchers.Default) { withTimeout(10_000) { WatchServiceLibraryWatcher().changes(location).first() } }
        delay(500) // let the watcher register its directories
        Files.writeString(courseDir.resolve("04 - IAM - Identity and Access Management/022 New lesson.html"), "<p>new</p>")

        firstChange.await()
    }
}
