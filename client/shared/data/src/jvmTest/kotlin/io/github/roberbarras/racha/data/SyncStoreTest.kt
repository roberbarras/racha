package io.github.roberbarras.racha.data

import io.github.roberbarras.racha.application.preferences.Preferences
import io.github.roberbarras.racha.application.shared.Clock
import io.github.roberbarras.racha.application.sync.SyncRecord
import io.github.roberbarras.racha.data.Fixtures.section
import io.github.roberbarras.racha.data.Fixtures.video
import io.github.roberbarras.racha.data.Fixtures.watched
import io.github.roberbarras.racha.application.library.EnsureBuiltInCategories
import io.github.roberbarras.racha.domain.learning.Progress
import io.github.roberbarras.racha.domain.preferences.ConflictPolicy
import io.github.roberbarras.racha.domain.preferences.Settings
import io.github.roberbarras.racha.domain.preferences.ThemeMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** ADR-0012 local side: the trigger outbox, outgoing changes and incoming last-writer-wins. */
class SyncStoreTest {
    private var now = Instant.parse("2026-11-16T10:00:00Z")
    private val clock = Clock { now }
    private val db = Fixtures.newDatabase()
    private val store = db.sync
    private val preferences = Preferences(db.settings, clock)
    private val item = Fixtures.item("AWS Course")
    private val intro = section(item, "01 - Introduction", 1)
    private val lesson = video(item, intro, "001 Course Introduction.mp4", 1)

    private suspend fun seedLibrary() {
        EnsureBuiltInCategories(db.categories, clock)()
        db.library.upsertItem(item)
        db.library.upsertSection(intro)
        db.library.upsertElement(lesson)
    }

    private fun record(entity: String, id: String, at: Instant, data: JsonObject, deleted: Boolean = false) =
        SyncRecord(entity, id, at, deleted, 0, data)

    private suspend fun pendingIds() = store.pending(1000).map { it.change.entity + ":" + it.change.id }

    @Test
    fun nothingIsRecordedWhileSyncIsOffAndEverythingIsSeededWhenItStarts() = runBlocking {
        seedLibrary()
        db.progress.save(watched(lesson, 30, now))
        assertTrue(store.pending(1000).isEmpty(), "sync off: no queue (a big first scan would fill it for nothing)")

        store.startRecording()
        val seeded = pendingIds()
        assertTrue("libraryItem:${item.id.value}" in seeded)
        assertTrue("element:${lesson.id.value}" in seeded)
        assertTrue("progress:${lesson.id.value}" in seeded)
        assertTrue(seeded.count { it.startsWith("category:") } >= 3, "built-in categories travel too")
    }

    @Test
    fun changesCollapseToTheCurrentRow() = runBlocking {
        seedLibrary()
        store.startRecording()
        store.acknowledge(store.pending(1000))
        db.progress.save(watched(lesson, 30, now))
        db.progress.save(watched(lesson, 90, now + 1.minutes))
        // The course percentage changes on every progress save: that alone must not queue the course.
        db.library.upsertItem(item.copy(progressPct = 12.5))

        val pending = store.pending(1000)
        assertEquals(listOf("progress"), pending.map { it.change.entity })
        assertEquals(90, pending.single().change.data["positionSec"]!!.jsonPrimitive.long)
        assertEquals(now + 1.minutes, pending.single().change.updatedAt)
    }

    @Test
    fun aChangeMadeWhilePushingIsKeptForTheNextRound() = runBlocking {
        seedLibrary()
        store.startRecording()
        store.acknowledge(store.pending(1000))
        db.progress.save(watched(lesson, 30, now))
        val inFlight = store.pending(1000)
        db.progress.save(watched(lesson, 60, now + 1.minutes)) // the player saves while the push is on the wire
        store.acknowledge(inFlight)
        assertEquals(60, store.pending(1000).single().change.data["positionSec"]!!.jsonPrimitive.long)
    }

    @Test
    fun remoteChangesAreAppliedNotQueuedBackAndOlderOnesLose() = runBlocking {
        seedLibrary()
        store.startRecording()
        store.acknowledge(store.pending(1000))
        db.progress.save(watched(lesson, 500, now - 1.hours).copy(maxPositionSec = 500))
        store.acknowledge(store.pending(1000))

        // Newer remote: the other device started over. Position follows it; the furthest point reached is kept.
        store.applyRemote(listOf(record("progress", lesson.id.value, now, buildJsonObject { put("positionSec", 20); put("completed", false) })), ConflictPolicy.ASK)
        val p = db.progress.get(lesson.id)!!
        assertEquals(20, p.positionSec)
        assertEquals(500, p.maxPositionSec)
        assertTrue(store.pending(1000).isEmpty(), "applied remote changes are not queued back")

        // Older remote: ignored.
        store.applyRemote(listOf(record("progress", lesson.id.value, now - 2.hours, buildJsonObject { put("positionSec", 999) })), ConflictPolicy.ASK)
        assertEquals(20, db.progress.get(lesson.id)!!.positionSec)
    }

    @Test
    fun settingsChangedOnBothSidesBecomeAQuestion() = runBlocking {
        seedLibrary()
        store.startRecording()
        store.acknowledge(store.pending(1000))
        preferences.set(Settings.Theme, ThemeMode.DARK) // changed here, not pushed yet
        now += 1.minutes
        store.applyRemote(listOf(record("setting", "user:general.theme", now, buildJsonObject { put("value", "light") })), ConflictPolicy.ASK)

        val conflict = store.observe().first().conflicts.single()
        assertEquals("general.theme", conflict.key)
        assertEquals(ThemeMode.DARK, preferences.get(Settings.Theme), "nothing changes until the user decides")

        store.resolve("general.theme", keepLocal = true)
        assertTrue(store.observe().first().conflicts.isEmpty())
        val again = store.pending(1000).single { it.change.entity == "setting" }
        assertEquals("user:general.theme", again.change.id)
        assertEquals(JsonPrimitive("dark"), again.change.data["value"])
    }

    @Test
    fun anOpenQuestionIsNotAnsweredByTheNextPull() = runBlocking {
        seedLibrary()
        store.startRecording()
        store.acknowledge(store.pending(1000))
        preferences.set(Settings.Theme, ThemeMode.DARK)
        now += 1.minutes
        val remote = record("setting", "user:general.theme", now, buildJsonObject { put("value", "light") })
        store.applyRemote(listOf(remote), ConflictPolicy.ASK) // the push came back stale: a question
        store.acknowledge(store.pending(1000)) // the pushed change leaves the queue
        store.applyRemote(listOf(remote), ConflictPolicy.ASK) // the same value arrives again with the pull
        assertEquals(ThemeMode.DARK, preferences.get(Settings.Theme))
        assertEquals(1, store.observe().first().conflicts.size)
    }

    @Test
    fun withLastWriteWinsTheNewerSettingIsTaken() = runBlocking {
        seedLibrary()
        store.startRecording()
        preferences.set(Settings.Theme, ThemeMode.DARK)
        now += 1.minutes
        store.applyRemote(listOf(record("setting", "user:general.theme", now, buildJsonObject { put("value", "light") })), ConflictPolicy.LAST_WRITE_WINS)
        assertEquals(ThemeMode.LIGHT, preferences.get(Settings.Theme))
        assertTrue(store.pending(1000).none { it.change.entity == "setting" && it.change.id == "user:general.theme" })
    }

    @Test
    fun deviceSettingsNeverLeaveTheDevice() = runBlocking {
        store.startRecording()
        store.acknowledge(store.pending(1000))
        preferences.set(Settings.SyncHost, "racha.example.com")
        assertTrue(store.pending(1000).isEmpty())
    }

    @Test
    fun deletionsTravelAsTombstones() = runBlocking {
        seedLibrary()
        store.startRecording()
        store.acknowledge(store.pending(1000))
        db.progress.save(Progress.start(lesson.id, 2710, now).recordPosition(10, 2710, now))
        store.acknowledge(store.pending(1000))
        db.database.libraryQueries.deleteProgress(lesson.id.value)
        val tombstone = store.pending(1000).single()
        assertEquals("progress", tombstone.change.entity)
        assertTrue(tombstone.change.deleted)
    }

    @Test
    fun rootsOfOtherDevicesAreOfferedForLinking() = runBlocking {
        seedLibrary()
        store.startRecording()
        store.applyRemote(listOf(record("categoryRoot", "root-on-phone", now, buildJsonObject { put("categoryId", "courses"); put("name", "Cursos") })), ConflictPolicy.ASK)
        assertEquals(listOf("Cursos"), store.remoteRoots("courses").map { it.name })
        // The built-in categories cannot be deleted from another device.
        store.applyRemote(listOf(record("category", "courses", now + 1.hours, JsonObject(emptyMap()), deleted = true)), ConflictPolicy.ASK)
        assertTrue(db.categories.all().any { it.key.value == "courses" })
    }
}
