package io.github.roberbarras.racha.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.roberbarras.racha.application.library.EnsureBuiltInCategories
import io.github.roberbarras.racha.application.shared.Clock
import io.github.roberbarras.racha.domain.learning.Progress
import io.github.roberbarras.racha.domain.library.CategoryKey
import io.github.roberbarras.racha.domain.library.ContentId
import io.github.roberbarras.racha.domain.library.ElementKind
import io.github.roberbarras.racha.domain.library.LibraryItem
import io.github.roberbarras.racha.domain.library.MediaInfo
import io.github.roberbarras.racha.domain.library.PlayableElement
import io.github.roberbarras.racha.domain.library.RelativePath
import io.github.roberbarras.racha.domain.library.RootKey
import io.github.roberbarras.racha.domain.library.Section
import io.github.roberbarras.racha.domain.library.SectionKind
import io.github.roberbarras.racha.domain.library.StructureKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.time.Instant

/** Runs the persistence adapter on the device's own SQLite (the dialect checks target Android 8's SQLite 3.18). */
@RunWith(AndroidJUnit4::class)
class AndroidDatabaseTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val db = AndroidDatabase.create(context, name = null)
    private val t0 = Instant.parse("2026-11-12T10:00:00Z")
    private val root = RootKey("0b4f1b5e-6d1f-4a35-9a51-6a3a1b0c9d10")

    @Test
    fun schemaQueriesAndConstraintsWorkOnTheDeviceSqlite() = runBlocking {
        EnsureBuiltInCategories(db.categories, Clock { t0 })()
        assertEquals(CategoryKey.BUILT_IN, db.categories.all().map { it.key })

        db.categories.setPrimary(CategoryKey.SERIES, t0) // partial unique index keeps a single primary
        assertEquals(listOf(CategoryKey.SERIES), db.categories.all().filter { it.isPrimary }.map { it.key })

        val itemPath = RelativePath.of("AWS Course")
        val item = LibraryItem(
            ContentId.forFolder(CategoryKey.COURSES, root, itemPath), CategoryKey.COURSES, root, itemPath,
            StructureKind.COURSE, "AWS Course", null, null, null, 0, null, 1, 268, 0.0, false, t0, t0,
        )
        db.library.upsertItem(item)
        val sectionPath = itemPath.resolve("01 - Introduction")
        val section = Section(ContentId.forFolder(CategoryKey.COURSES, root, sectionPath), item.id, SectionKind.MODULE, "Introduction", null, 1, null, t0)
        db.library.upsertSection(section)
        val videoPath = sectionPath.resolve("001 Course Introduction.mp4")
        val video = PlayableElement(
            ContentId.forVideo(CategoryKey.COURSES, root, videoPath), item.id, section.id, ElementKind.VIDEO, null,
            "Course Introduction", null, videoPath, MediaInfo(268, 1920, 1080, "h264", 30.0, 2152), 1, null, false,
            82_339_527, t0, null, t0, t0,
        )
        db.library.upsertElement(video)
        assertEquals(listOf(video), db.library.elements(item.id))

        db.progress.save(Progress.start(video.id, 268, t0).recordPosition(120, null, t0))
        assertEquals(listOf(120L), db.progress.observeContinueWatching(10).first().map { it.progress.positionSec })

        assertFails { db.library.upsertSection(section.copy(id = ContentId("0".repeat(64)), libraryItemId = ContentId("1".repeat(64)))) }
        Unit
    }
}
