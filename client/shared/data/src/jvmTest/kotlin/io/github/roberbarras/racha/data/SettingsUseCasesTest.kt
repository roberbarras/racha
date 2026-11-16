package io.github.roberbarras.racha.data

import io.github.roberbarras.racha.application.browse.ManageCategories
import io.github.roberbarras.racha.application.library.EnsureBuiltInCategories
import io.github.roberbarras.racha.application.library.StorageMaintenance
import io.github.roberbarras.racha.application.library.ThumbnailCache
import io.github.roberbarras.racha.application.library.scan.HashEngagedElements
import io.github.roberbarras.racha.application.library.scan.KeepLibraryUpToDate
import io.github.roberbarras.racha.application.library.scan.LocalDates
import io.github.roberbarras.racha.application.library.scan.RefreshThumbnails
import io.github.roberbarras.racha.application.library.scan.ScanLibrary
import io.github.roberbarras.racha.application.preferences.ConfigTransfer
import io.github.roberbarras.racha.application.preferences.InvalidConfigFile
import io.github.roberbarras.racha.application.preferences.Preferences
import io.github.roberbarras.racha.application.shared.Clock
import io.github.roberbarras.racha.application.library.scan.LibrarySource
import io.github.roberbarras.racha.domain.library.scan.FileEntry
import io.github.roberbarras.racha.data.Fixtures.item
import io.github.roberbarras.racha.data.Fixtures.section
import io.github.roberbarras.racha.data.Fixtures.video
import io.github.roberbarras.racha.domain.learning.HistoryEntry
import io.github.roberbarras.racha.domain.library.CategoryKey
import io.github.roberbarras.racha.domain.library.CategoryRoot
import io.github.roberbarras.racha.domain.library.Mode
import io.github.roberbarras.racha.domain.library.RelativePath
import io.github.roberbarras.racha.domain.library.scan.CalendarDate
import io.github.roberbarras.racha.domain.preferences.Settings
import io.github.roberbarras.racha.domain.preferences.ThemeMode
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Settings (spec §9): categories, export/import (ADR-0010) and storage maintenance. */
class SettingsUseCasesTest {
    private val now = Instant.parse("2026-11-16T10:00:00Z")
    private val clock = Clock { now }
    private val db = Fixtures.newDatabase()
    private val preferences = Preferences(db.settings, clock)
    private val source = object : LibrarySource {
        override fun displayName(location: String) = location
        override suspend fun list(location: String, descend: (RelativePath) -> Boolean) = emptyList<FileEntry>()
        override fun uri(location: String, path: RelativePath) = location
    }
    private val utc = LocalDates { CalendarDate(2026, 11, 16) }
    private val scan = ScanLibrary(db.categories, db.library, db.progress, source, { _, _ -> null }, { _, _ -> "" }, preferences, clock, utc)
    private val categories = ManageCategories(db.categories, scan, clock)
    private val transfer = ConfigTransfer(preferences, db.categories, clock, "1.0.0")

    @Test
    fun categoriesCanBeCreatedRenamedReorderedHiddenAndDeleted() = runBlocking<Unit> {
        EnsureBuiltInCategories(db.categories, clock)()
        val docs = categories.create("  Documentales ")
        assertEquals("Documentales", docs.name)
        assertEquals(3, docs.order)

        categories.move(docs.key, -1)
        assertEquals(listOf("courses", "series", docs.key.value, "live"), db.categories.all().sortedBy { it.order }.map { it.key.value })
        categories.rename(CategoryKey.SERIES, "Mis series")
        assertEquals("Mis series", db.categories.get(CategoryKey.SERIES)!!.name)
        categories.rename(CategoryKey.SERIES, " ")
        assertNull(db.categories.get(CategoryKey.SERIES)!!.name, "a blank name gives a built-in its translated label back")
        categories.setVisibility(docs.key, study = false, leisure = true)
        assertEquals(false to true, db.categories.get(docs.key)!!.let { it.visibleInStudy to it.visibleInLeisure })
        categories.setExcludes(docs.key, listOf("temp/", " *.part ", "", "temp/"))
        assertEquals(listOf("temp/", "*.part"), db.categories.get(docs.key)!!.excludePatterns)

        categories.setPrimary(docs.key)
        categories.delete(docs.key)
        assertNull(db.categories.get(docs.key))
        assertEquals(CategoryKey.COURSES, db.categories.all().single { it.isPrimary }.key, "another category becomes primary")
        assertFailsWith<IllegalArgumentException> { categories.delete(CategoryKey.LIVE) }
    }

    @Test
    fun configurationRoundTripsToAnotherDevice() = runBlocking<Unit> {
        EnsureBuiltInCategories(db.categories, clock)()
        preferences.set(Settings.Theme, ThemeMode.DARK)
        preferences.set(Settings.SeekStepSeconds, 30)
        preferences.set(Settings.Mode, Mode.LEISURE)
        preferences.set(Settings.SyncEnabled, true)
        val docs = categories.create("Documentales")
        categories.setPrimary(docs.key)
        categories.setVisibility(CategoryKey.LIVE, study = false, leisure = true)
        val file = transfer.export()
        assertTrue("\"format\": \"racha-config\"" in file && "\"schemaVersion\": 1" in file)
        assertTrue("sync.enabled" !in file, "turning sync on needs a sign-in")

        val other = Fixtures.newDatabase()
        val otherPreferences = Preferences(other.settings, clock)
        EnsureBuiltInCategories(other.categories, clock)()
        val report = ConfigTransfer(otherPreferences, other.categories, clock, "1.0.0").import(file)
        assertEquals(ThemeMode.DARK, otherPreferences.get(Settings.Theme))
        assertEquals(30, otherPreferences.get(Settings.SeekStepSeconds))
        assertEquals(Mode.LEISURE, otherPreferences.get(Settings.Mode))
        assertEquals(false, otherPreferences.get(Settings.SyncEnabled))
        assertEquals("Documentales", other.categories.get(docs.key)!!.name)
        assertEquals(docs.key, other.categories.all().single { it.isPrimary }.key)
        assertEquals(false, other.categories.get(CategoryKey.LIVE)!!.visibleInStudy)
        assertTrue(report.settings >= 3 && report.categories >= 2)
    }

    @Test
    fun badFilesAndBadValuesAreRejectedWithoutDamage() = runBlocking<Unit> {
        EnsureBuiltInCategories(db.categories, clock)()
        assertFailsWith<InvalidConfigFile> { transfer.import("{}") }
        assertFailsWith<InvalidConfigFile> { transfer.import("not json") }
        assertFailsWith<InvalidConfigFile> { transfer.import("""{"format":"racha-config","schemaVersion":99}""") }
        val report = transfer.import(
            """{"format":"racha-config","schemaVersion":1,"settings":{"player.seekStepSeconds":"7","general.theme":"dark","made.up":"1"},
               "categories":[{"key":"NOT A KEY"}]}""",
        )
        assertEquals(1, report.settings)
        assertEquals(3, report.skipped)
        assertEquals(10, preferences.get(Settings.SeekStepSeconds), "7 is not an allowed step")
    }

    @Test
    fun storageMaintenanceClearsCachesAndLocalHistory() = runBlocking<Unit> {
        EnsureBuiltInCategories(db.categories, clock)()
        db.categories.upsertRoot(CategoryRoot(Fixtures.root, CategoryKey.COURSES, "file:///cursos", 0))
        val course = item().copy(posterPath = "file:///cache/thumbs/abc.webp")
        val folderArt = item("Other").copy(posterPath = "file:///cursos/Other/poster.jpg")
        val intro = section(course, "01", 1)
        val lesson = video(course, intro, "001 Intro.mp4", 1)
        listOf(course, folderArt).forEach { db.library.upsertItem(it) }
        db.library.upsertSection(intro)
        db.library.upsertElement(lesson)
        db.history.add(HistoryEntry("h1", lesson.id, now, 10))
        var cleared = false
        val cache = object : ThumbnailCache {
            override val locationPrefix = "file:///cache/thumbs/"
            override suspend fun clear(): Long = 1024L.also { cleared = true }
        }
        val keep = KeepLibraryUpToDate(
            db.categories, scan, HashEngagedElements(db.categories, db.library) { _, _ -> "" },
            RefreshThumbnails(db.categories, db.library, { _, _, _, _, _ -> null }, preferences), { emptyFlow() }, preferences,
        )
        val storage = StorageMaintenance(db.library, db.history, cache, keep)

        assertEquals(1024, storage.clearThumbnails())
        assertTrue(cleared)
        assertNull(db.library.item(course.id)!!.posterPath)
        assertEquals("file:///cursos/Other/poster.jpg", db.library.item(folderArt.id)!!.posterPath, "folder artwork is not a cache")

        storage.clearHistory()
        assertTrue(db.history.observeRecent(10).first().isEmpty())
    }
}
