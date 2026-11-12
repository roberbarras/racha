package io.github.roberbarras.racha.data

import io.github.roberbarras.racha.data.Fixtures.courses
import io.github.roberbarras.racha.data.Fixtures.item
import io.github.roberbarras.racha.data.Fixtures.root
import io.github.roberbarras.racha.data.Fixtures.section
import io.github.roberbarras.racha.data.Fixtures.t0
import io.github.roberbarras.racha.data.Fixtures.video
import io.github.roberbarras.racha.domain.library.CategoryKey
import io.github.roberbarras.racha.domain.library.CategoryRoot
import io.github.roberbarras.racha.domain.library.ContentId
import io.github.roberbarras.racha.domain.library.ElementKind
import io.github.roberbarras.racha.domain.library.LanguageCode
import io.github.roberbarras.racha.domain.library.RelativePath
import io.github.roberbarras.racha.domain.library.ResourceType
import io.github.roberbarras.racha.domain.library.Subtitle
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes

class LibraryPersistenceTest {
    private val db = Fixtures.newDatabase()

    @Test
    fun categoriesRoundTripAndKeepASinglePrimary() = runBlocking {
        Fixtures.courses().let { db.categories.upsert(it) }
        val series = io.github.roberbarras.racha.domain.library.Category.builtIns(t0)[1]
        db.categories.upsert(series)

        db.categories.setPrimary(series.key, t0 + 1.minutes)

        assertEquals(listOf(series.key), db.categories.all().filter { it.isPrimary }.map { it.key })
        assertEquals(courses().copy(isPrimary = false, updatedAt = t0 + 1.minutes), db.categories.get(CategoryKey.COURSES))

        db.categories.upsert(courses()) // primary again: the previous primary is cleared in the same transaction
        assertEquals(listOf(CategoryKey.COURSES), db.categories.all().filter { it.isPrimary }.map { it.key })
    }

    @Test
    fun deletingACategoryCascadesToRootsAndIndexedContent() = runBlocking {
        db.categories.upsert(courses())
        db.categories.upsertRoot(CategoryRoot(root, CategoryKey.COURSES, "file:///media/cursos", 0))
        val item = item()
        db.library.upsertItem(item)

        db.categories.delete(CategoryKey.COURSES)

        assertEquals(emptyList(), db.categories.roots(CategoryKey.COURSES))
        assertNull(db.library.item(item.id))
    }

    @Test
    fun itemsSectionsElementsAndSubtitlesRoundTripExactly() = runBlocking {
        db.categories.upsert(courses())
        val item = item().copy(titleOverride = "AWS — mi curso", description = "SAA-C03", totalItems = 2, totalDurationSec = 536, progressPct = 12.5)
        db.library.upsertItem(item)
        val section = section(item, "01 - Introduction", 1)
        db.library.upsertSection(section)
        val element = video(item, section, "001 Course Introduction.mp4", 1, hash = "f".repeat(64))
        db.library.upsertElement(element)
        val resourcePath = RelativePath.of("AWS Course/01 - Introduction/003 AWS Account Activation Troubleshooting.html")
        val resource = element.copy(
            id = ContentId.forResource(CategoryKey.COURSES, root, resourcePath),
            kind = ElementKind.RESOURCE,
            resourceType = ResourceType.HTML,
            relativePath = resourcePath,
            order = 3,
            fileHash = null,
        )
        db.library.upsertElement(resource)
        val subtitlePath = RelativePath.of("AWS Course/01 - Introduction/001 Course Introduction_es.srt")
        val subtitle = Subtitle(ContentId.forSubtitle(CategoryKey.COURSES, root, subtitlePath), element.id, LanguageCode("es"), subtitlePath, t0)
        db.library.upsertSubtitle(subtitle)

        assertEquals(item, db.library.item(item.id))
        assertEquals(listOf(section), db.library.sections(item.id))
        assertEquals(listOf(element, resource), db.library.elements(item.id))
        assertEquals(listOf(element), db.library.elementsWithHash("f".repeat(64)))
        assertEquals(listOf(subtitle), db.library.subtitles(element.id))
    }

    @Test
    fun elementsAreOrderedBySectionThenElementHonouringUserOverrides() = runBlocking {
        db.categories.upsert(courses())
        val item = item()
        db.library.upsertItem(item)
        val intro = section(item, "01 - Introduction", 1)
        val iam = section(item, "04 - IAM", 4)
        listOf(intro, iam).forEach { db.library.upsertSection(it) }
        val a = video(item, intro, "001 A.mp4", 1)
        val b = video(item, intro, "002 B.mp4", 2).copy(orderOverride = 0) // user moved B before A
        val c = video(item, iam, "001 C.mp4", 1)
        listOf(c, a, b).forEach { db.library.upsertElement(it) }

        assertEquals(listOf(b, a, c).map { it.id }, db.library.elements(item.id).map { it.id })
    }

    @Test
    fun upsertUpdatesInPlaceWithoutTouchingChildren() = runBlocking {
        db.categories.upsert(courses())
        val item = item()
        db.library.upsertItem(item)
        val section = section(item, "01 - Introduction", 1)
        db.library.upsertSection(section)
        db.library.upsertElement(video(item, section, "001 A.mp4", 1))

        db.library.upsertItem(item.copy(title = "Renamed", archived = true))

        assertEquals("Renamed", db.library.item(item.id)?.title)
        assertEquals(1, db.library.elements(item.id).size) // an INSERT OR REPLACE would have cascaded these away
    }
}
