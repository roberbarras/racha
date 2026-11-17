package io.github.roberbarras.racha.data

import io.github.roberbarras.racha.application.backup.FullBackup
import io.github.roberbarras.racha.application.backup.InvalidBackupFile
import io.github.roberbarras.racha.application.library.EnsureBuiltInCategories
import io.github.roberbarras.racha.application.library.scan.LibrarySource
import io.github.roberbarras.racha.application.library.scan.LocalDates
import io.github.roberbarras.racha.application.library.scan.ScanLibrary
import io.github.roberbarras.racha.application.playback.PlaybackController
import io.github.roberbarras.racha.application.preferences.Preferences
import io.github.roberbarras.racha.application.shared.Clock
import io.github.roberbarras.racha.domain.learning.HistoryEntry
import io.github.roberbarras.racha.domain.learning.LocalStat
import io.github.roberbarras.racha.domain.learning.Progress
import io.github.roberbarras.racha.domain.library.CategoryKey
import io.github.roberbarras.racha.domain.library.CategoryRoot
import io.github.roberbarras.racha.domain.library.RelativePath
import io.github.roberbarras.racha.domain.library.RootKey
import io.github.roberbarras.racha.domain.library.RootLayout
import io.github.roberbarras.racha.domain.library.scan.CalendarDate
import io.github.roberbarras.racha.domain.library.scan.FileEntry
import io.github.roberbarras.racha.domain.playback.PlaybackChoice
import io.github.roberbarras.racha.domain.playback.SubtitleChoice
import io.github.roberbarras.racha.domain.preferences.Settings
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** The whole of a device's Racha to a file and onto another device (H16, ADR-0015). */
class FullBackupTest {
    private val now = Instant.parse("2026-11-17T20:00:00Z")
    private val clock = Clock { now }
    private val root = RootKey("6d0f8a52-1c1b-4c55-9a51-0e5d3c2b1a10")
    private val location = "file:///media/Python desde cero"

    /** The same files on both devices (the same course copied to the new one). */
    private val files = object : LibrarySource {
        override fun displayName(location: String) = location.substringAfterLast('/')
        override suspend fun list(location: String, descend: (RelativePath) -> Boolean) =
            if (location != this@FullBackupTest.location) emptyList() else listOf("Introducción/01 Bienvenida.mp4", "Fundamentos/01 Variables.mp4")
                .map { FileEntry(RelativePath.of(it), isDirectory = false, size = 1_000, modifiedAt = Instant.parse("2026-11-01T10:00:00Z")) }
        override fun uri(location: String, path: RelativePath) = "$location/${path.value}"
    }

    private inner class Device {
        val db = Fixtures.newDatabase()
        val preferences = Preferences(db.settings, clock)
        val scan = ScanLibrary(db.categories, db.library, db.progress, files, { _, _ -> null }, { _, _ -> "" }, preferences, clock, LocalDates { CalendarDate(2026, 11, 17) })
        val backup = FullBackup(db.sync, db.categories, preferences, db.playbackMemory, db.stats, scan, clock, "Racha test")

        suspend fun course() = db.library.itemsInRoot(CategoryKey.COURSES, root).single { !it.archived }
    }

    private suspend fun original(): Device = Device().apply {
        EnsureBuiltInCategories(db.categories, clock)()
        db.categories.upsertRoot(CategoryRoot(root, CategoryKey.COURSES, location, 0, RootLayout.SINGLE_ITEM))
        scan.all()
        val course = course()
        db.library.upsertItem(course.copy(titleOverride = "Mi curso de Python", updatedAt = now))
        val lesson = db.library.elements(course.id).first { it.title == "Variables" }
        db.progress.save(Progress.start(lesson.id, 600, now).recordPosition(600, 600, now).finish(now))
        db.history.add(HistoryEntry("h1", lesson.id, now, 600))
        preferences.set(Settings.DefaultSpeed, 1.5) // per user: travels as a sync record
        preferences.set(Settings.HardwareDecoding, true) // per device: in the device part
        preferences.set(Settings.SyncEnabled, true) // never restored by a file
        db.playbackMemory.save(course.id.value, PlaybackChoice(1.25, "eng", 1, SubtitleChoice.Language("es")), now)
        db.stats.save(LocalStat(PlaybackController.hourMetric(21), PlaybackController.ALL_TIME, 3_600.0, now))
    }

    @Test
    fun everythingComesBackOnAnotherDeviceOnce() = runBlocking<Unit> {
        val text = original().backup.export()
        val json = Json.parseToJsonElement(text).jsonObject
        assertEquals("racha-backup", json["format"]!!.jsonPrimitive.content)
        assertEquals(location, json["device"]!!.jsonObject["roots"]!!.jsonArray.single().jsonObject["location"]!!.jsonPrimitive.content)
        assertFalse("sync.enabled" in text, "turning sync on needs signing in")

        val fresh = Device()
        EnsureBuiltInCategories(fresh.db.categories, clock)()
        val report = fresh.backup.import(text)
        assertEquals(1, report.folders)

        val restoredRoot = fresh.db.categories.roots(CategoryKey.COURSES).single()
        assertEquals(Triple(root, location, RootLayout.SINGLE_ITEM), Triple(restoredRoot.key, restoredRoot.location, restoredRoot.layout))
        val course = fresh.course()
        assertEquals("Mi curso de Python", course.displayTitle, "edits apply to the course scanned again")
        val lesson = fresh.db.library.elements(course.id).first { it.title == "Variables" }
        assertTrue(fresh.db.progress.get(lesson.id)!!.completed)
        assertTrue(course.progressPct > 0, "the course knows its progress")
        assertEquals(1.5, fresh.preferences.get(Settings.DefaultSpeed))
        assertTrue(fresh.preferences.get(Settings.HardwareDecoding))
        assertFalse(fresh.preferences.get(Settings.SyncEnabled))
        assertEquals(1.25, fresh.db.playbackMemory.get(course.id.value)?.speed)
        assertEquals(3_600.0, fresh.db.stats.get(PlaybackController.hourMetric(21), PlaybackController.ALL_TIME)?.value)
        assertEquals(1, fresh.db.history.watchTimesSince(now.minus(kotlin.time.Duration.parse("1d"))).size)

        // Again: nothing doubles.
        val again = fresh.backup.import(text)
        assertEquals(0, again.folders)
        assertEquals(1, fresh.db.categories.roots(CategoryKey.COURSES).size)
        assertEquals(1, fresh.db.history.watchTimesSince(now.minus(kotlin.time.Duration.parse("1d"))).size)
        assertEquals(3_600.0, fresh.db.stats.get(PlaybackController.hourMetric(21), PlaybackController.ALL_TIME)?.value, "totals keep the larger, not the sum")
    }

    @Test
    fun aServersFileRestoresWithoutFoldersAndOtherFilesAreRefused() = runBlocking<Unit> {
        val device = Device()
        EnsureBuiltInCategories(device.db.categories, clock)()
        val server = """{"format":"racha-backup","schemaVersion":1,"source":{"kind":"server"},
            "records":[{"entity":"setting","id":"user:player.defaultSpeed","updatedAt":"2026-11-17T19:00:00Z","deleted":false,"data":{"value":"2.0"}}],
            "events":[]}"""
        val report = device.backup.import(server)
        assertEquals(0, report.folders)
        assertEquals(2.0, device.preferences.get(Settings.DefaultSpeed))

        assertFailsWith<InvalidBackupFile> { device.backup.import("""{"format":"racha-config","schemaVersion":1}""") }
        assertFailsWith<InvalidBackupFile> { device.backup.import("""{"format":"racha-backup","schemaVersion":7}""") }
        assertFailsWith<InvalidBackupFile> { device.backup.import("not json") }
        assertNotNull(device.preferences.get(Settings.DefaultSpeed))
    }
}
