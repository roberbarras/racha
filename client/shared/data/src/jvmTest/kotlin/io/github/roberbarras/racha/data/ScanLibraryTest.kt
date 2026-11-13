package io.github.roberbarras.racha.data

import io.github.roberbarras.racha.application.library.EnsureBuiltInCategories
import io.github.roberbarras.racha.application.library.scan.FileHasher
import io.github.roberbarras.racha.application.library.scan.HashEngagedElements
import io.github.roberbarras.racha.application.library.scan.LibrarySource
import io.github.roberbarras.racha.application.library.scan.LocalDates
import io.github.roberbarras.racha.application.library.scan.MediaProbe
import io.github.roberbarras.racha.application.library.scan.RefreshThumbnails
import io.github.roberbarras.racha.application.library.scan.ScanLibrary
import io.github.roberbarras.racha.application.library.scan.ThumbnailRenderer
import io.github.roberbarras.racha.application.preferences.Preferences
import io.github.roberbarras.racha.application.shared.Clock
import io.github.roberbarras.racha.domain.learning.Bookmark
import io.github.roberbarras.racha.domain.learning.Progress
import io.github.roberbarras.racha.domain.library.CategoryKey
import io.github.roberbarras.racha.domain.library.CategoryRoot
import io.github.roberbarras.racha.domain.library.ContentId
import io.github.roberbarras.racha.domain.library.ElementKind
import io.github.roberbarras.racha.domain.library.LanguageCode
import io.github.roberbarras.racha.domain.library.MediaInfo
import io.github.roberbarras.racha.domain.library.RelativePath
import io.github.roberbarras.racha.domain.library.RootKey
import io.github.roberbarras.racha.domain.library.StructureKind
import io.github.roberbarras.racha.domain.library.scan.CalendarDate
import io.github.roberbarras.racha.domain.library.scan.FileEntry
import io.github.roberbarras.racha.domain.preferences.ChangeDetection
import io.github.roberbarras.racha.domain.preferences.Settings
import io.github.roberbarras.racha.domain.shared.Sha256
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** The scan use case against the real SQLite adapter, with an in-memory file system and media probe. */
class ScanLibraryTest {
    private var now = Instant.parse("2026-11-13T09:00:00Z")
    private val db = Fixtures.newDatabase()
    private val preferences = Preferences(db.settings, Clock { now })
    private val files = FakeFiles()
    private val probe = FakeProbe()
    private val hasher = FakeHasher(files)
    private val root = RootKey("0b4f1b5e-6d1f-4a35-9a51-6a3a1b0c9d10")
    private val location = "file:///media/AWS Certified Solutions Architect"
    private val scan = ScanLibrary(
        db.categories, db.library, db.progress, files, probe, hasher, preferences, { now }, LocalDates { CalendarDate(2026, 11, 13) },
    )

    private fun setUp() = runBlocking {
        EnsureBuiltInCategories(db.categories, Clock { now })()
        db.categories.upsertRoot(CategoryRoot(root, CategoryKey.COURSES, location, 0))
        SPEC_EXAMPLE.forEach { files.put(it, content = "content of $it") }
    }

    private fun id(path: String) = ContentId.forVideo(CategoryKey.COURSES, root, RelativePath.of(path))

    private suspend fun course() = db.library.itemsInRoot(CategoryKey.COURSES, root).single { !it.archived }

    @Test
    fun specExampleIsIndexedEndToEnd() = runBlocking {
        setUp()

        val report = scan.all()

        assertEquals(8, report.added)
        assertEquals(3, report.probed, "only videos are probed")
        val course = course()
        assertEquals("AWS Certified Solutions Architect", course.title)
        assertEquals(StructureKind.COURSE, course.kind)
        assertEquals(8, course.totalItems)
        assertEquals(listOf("Introduction", "Code & Slides Download", "IAM - Identity and Access Management"), db.library.sections(course.id).map { it.title })
        val elements = db.library.elements(course.id)
        assertEquals(
            listOf(
                "Course Introduction", "Creating an AWS Account", "AWS Account Activation Troubleshooting",
                "Code & Slides Download", "Course-Material-Download", "external-links",
                "IAM Introduction Users, Groups, Policies", "Identity and Access Management - Quiz",
            ),
            elements.map { it.title },
        )
        assertTrue(elements.none { it.relativePath.value.startsWith("temp/") })
        val videos = elements.filter { it.kind == ElementKind.VIDEO }
        assertEquals(3, videos.size)
        assertTrue(videos.all { v -> db.library.subtitles(v.id).map { it.language } == listOf(LanguageCode("es")) })
        assertEquals(60L, videos.first().media.durationSec)
        assertEquals(listOf(course.id), report.newItems)
    }

    @Test
    fun rescanningUnchangedFilesProbesNothingAndKeepsUserEdits() = runBlocking {
        setUp()
        scan.all()
        val first = id("01 - Introduction/001 Course Introduction.mp4")
        db.library.upsertElement(db.library.element(first)!!.copy(titleOverride = "Bienvenida", orderOverride = 9))
        db.library.upsertItem(course().copy(titleOverride = "AWS SAA"))
        probe.calls.clear()

        val report = scan.all()

        assertEquals(0, report.probed)
        assertEquals(8, report.unchanged)
        assertEquals(0, report.added)
        assertEquals("Bienvenida" to 9, db.library.element(first)!!.let { it.titleOverride to it.orderOverride })
        assertEquals("AWS SAA", course().titleOverride)
    }

    @Test
    fun modifiedFilesAreReprobed() = runBlocking {
        setUp()
        scan.all()
        probe.calls.clear()
        files.put("01 - Introduction/002 Creating an AWS Account.mp4", "re-encoded", modifiedAt = now + 5.minutes)

        val report = scan.all()

        assertEquals(1, report.updated)
        assertEquals(listOf("01 - Introduction/002 Creating an AWS Account.mp4"), probe.calls)
    }

    @Test
    fun deletedFilesAreArchivedWithTheirProgressAndComeBackWhenRestored() = runBlocking {
        setUp()
        scan.all()
        val video = id("04 - IAM - Identity and Access Management/001 IAM Introduction Users, Groups, Policies.mp4")
        db.progress.save(Progress.start(video, 60, now).recordPosition(30, null, now))
        val saved = files.remove("04 - IAM - Identity and Access Management/001 IAM Introduction Users, Groups, Policies.mp4")

        val deletion = scan.all()

        assertEquals(1, deletion.archived)
        val archived = assertNotNull(db.library.element(video), "deleting a file must not delete it from the app")
        assertTrue(archived.archived)
        assertEquals(30L, db.progress.get(video)?.positionSec)
        assertEquals(7, course().totalItems)

        files.put(saved)
        val restore = scan.all()

        assertEquals(1, restore.restored)
        assertFalse(db.library.element(video)!!.archived)
        assertEquals(30L, db.progress.get(video)?.positionSec)
    }

    @Test
    fun movingAWatchedFileKeepsItsProgressThroughTheContentHash() = runBlocking {
        setUp()
        scan.all()
        val oldPath = "01 - Introduction/002 Creating an AWS Account.mp4"
        val newPath = "04 - IAM - Identity and Access Management/005 Creating an AWS Account.mp4"
        db.progress.save(Progress.start(id(oldPath), 60, now).recordPosition(42, null, now))
        db.bookmarks.save(Bookmark("b1", id(oldPath), 10, "billing alarm", now, now))
        assertEquals(1, HashEngagedElements(db.categories, db.library, hasher)(), "only the watched element is hashed")

        files.move(oldPath, newPath)
        val report = scan.all()

        assertEquals(1, report.relinked)
        assertEquals(42L, db.progress.get(id(newPath))?.positionSec)
        assertEquals(listOf("b1"), db.bookmarks.observeOnce(id(newPath)))
        assertNull(db.progress.get(id(oldPath)))
        assertNull(db.library.element(id(oldPath)), "the archived duplicate is replaced by the moved file")
    }

    @Test
    fun movingAnUnwatchedFileNeverReadsWholeFiles() = runBlocking {
        setUp()
        scan.all()
        hasher.calls.clear()

        files.move("01 - Introduction/001 Course Introduction.mp4", "01 - Introduction/001 Welcome.mp4")
        val report = scan.all()

        assertEquals(1, report.added)
        assertEquals(1, report.archived)
        assertEquals(emptyList(), hasher.calls)
    }

    @Test
    fun hashModeSkipsReprobingFilesWhoseContentDidNotChange() = runBlocking {
        setUp()
        preferences.set(Settings.ChangeDetectionMode, ChangeDetection.HASH)
        scan.all()
        val path = "01 - Introduction/001 Course Introduction.mp4"
        db.progress.save(Progress.start(id(path), 60, now))
        HashEngagedElements(db.categories, db.library, hasher)()
        probe.calls.clear()

        files.touch(path, now + 1.minutes) // copied back: new mtime, same bytes
        val report = scan.all()

        assertEquals(emptyList(), probe.calls)
        assertEquals(8, report.unchanged)
    }

    @Test
    fun tempIsHiddenByDefaultAndIndexedWhenTheExclusionIsRemoved() = runBlocking {
        setUp()
        scan.all()
        assertTrue(db.library.elements(course().id).none { it.relativePath.value.startsWith("temp/") })

        val courses = db.categories.get(CategoryKey.COURSES)!!
        db.categories.upsert(courses.copy(excludePatterns = emptyList()))
        scan.all()

        assertTrue(db.library.elements(course().id).any { it.relativePath.value == "temp/index_XXXXX_1920x1080.m3u8" })
    }

    @Test
    fun itemProgressCountsCompletedAndPartialElements() = runBlocking {
        setUp()
        scan.all()
        val elements = db.library.elements(course().id)
        db.progress.save(Progress.start(elements[0].id, 60, now).finish(now))
        db.progress.save(Progress.start(elements[1].id, 60, now).recordPosition(30, null, now))

        scan.all()

        assertEquals((1.0 + 0.5) / 8 * 100, course().progressPct, 0.001)
    }

    @Test
    fun postersComeFromFolderArtworkOrARenderedFrame() = runBlocking {
        setUp()
        val renders = mutableListOf<String>()
        val renderer = ThumbnailRenderer { _, path, _, fraction, _ -> renders += "${path.value}@$fraction"; "file:///cache/thumb.webp" }
        scan.all()

        assertEquals(1, RefreshThumbnails(db.categories, db.library, renderer, preferences)())
        assertEquals(listOf("01 - Introduction/001 Course Introduction.mp4@0.1"), renders)
        assertEquals("file:///cache/thumb.webp", course().posterPath)

        files.put("cover.jpg", "artwork")
        scan.all()
        assertEquals("$location/cover.jpg", course().posterPath)

        db.library.upsertItem(course().copy(posterPath = null))
        preferences.set(Settings.Thumbnails, false)
        assertEquals(0, RefreshThumbnails(db.categories, db.library, renderer, preferences)())
    }

    private suspend fun SqlBookmarkRepository.observeOnce(element: ContentId) = observe(element).first().map { it.id }

    private class FakeFile(val path: RelativePath, val content: String, val modifiedAt: Instant)

    private inner class FakeFiles : LibrarySource {
        private val files = linkedMapOf<RelativePath, FakeFile>()

        fun put(path: String, content: String, modifiedAt: Instant = Instant.parse("2026-11-01T10:00:00Z")) =
            put(FakeFile(RelativePath.of(path), content, modifiedAt))

        fun put(file: FakeFile) {
            files[file.path] = file
        }

        fun remove(path: String): FakeFile = files.remove(RelativePath.of(path))!!

        fun move(from: String, to: String) {
            val file = remove(from)
            put(FakeFile(RelativePath.of(to), file.content, file.modifiedAt))
        }

        fun touch(path: String, at: Instant) {
            val file = files.getValue(RelativePath.of(path))
            put(FakeFile(file.path, file.content, at))
        }

        fun content(path: RelativePath) = files.getValue(path).content

        override fun displayName(location: String) = location.substringAfterLast('/')

        override suspend fun list(location: String, descend: (RelativePath) -> Boolean): List<FileEntry> {
            val visibleDirs = mutableSetOf<RelativePath>()
            return files.values.mapNotNull { file ->
                var dir = file.path.parent
                val ancestors = mutableListOf<RelativePath>()
                while (!dir.isRoot) { ancestors += dir; dir = dir.parent }
                if (ancestors.any { !descend(it) }) return@mapNotNull null
                visibleDirs += ancestors
                FileEntry(file.path, isDirectory = false, size = file.content.length.toLong(), modifiedAt = file.modifiedAt)
            } + visibleDirs.map { FileEntry(it, isDirectory = true, size = 0, modifiedAt = Instant.parse("2026-11-01T10:00:00Z")) }
        }

        override fun uri(location: String, path: RelativePath) = "$location/${path.value}"
    }

    private class FakeProbe : MediaProbe {
        val calls = mutableListOf<String>()

        override suspend fun probe(location: String, path: RelativePath): MediaInfo {
            calls += path.value
            return MediaInfo(60, 1920, 1080, "h264", 30.0, 2000)
        }
    }

    private class FakeHasher(private val files: ScanLibraryTest.FakeFiles) : FileHasher {
        val calls = mutableListOf<String>()

        override suspend fun sha256(location: String, path: RelativePath): String {
            calls += path.value
            return Sha256.hex(files.content(path).encodeToByteArray())
        }
    }

    private companion object {
        val SPEC_EXAMPLE = listOf(
            "01 - Introduction/001 Course Introduction.mp4",
            "01 - Introduction/001 Course Introduction_es.srt",
            "01 - Introduction/002 Creating an AWS Account.mp4",
            "01 - Introduction/002 Creating an AWS Account_es.srt",
            "01 - Introduction/003 AWS Account Activation Troubleshooting.html",
            "02 - Code & Slides Download/001 Code & Slides Download.html",
            "02 - Code & Slides Download/001 Course-Material-Download.url",
            "02 - Code & Slides Download/external-links.txt",
            "04 - IAM - Identity and Access Management/001 IAM Introduction Users, Groups, Policies.mp4",
            "04 - IAM - Identity and Access Management/001 IAM Introduction Users, Groups, Policies_es.srt",
            "04 - IAM - Identity and Access Management/021 Identity and Access Management - Quiz.html",
            "temp/index_XXXXX_1920x1080.m3u8",
            "temp/index_XXXXX_1920x1080_00001.ts",
        )
    }
}
