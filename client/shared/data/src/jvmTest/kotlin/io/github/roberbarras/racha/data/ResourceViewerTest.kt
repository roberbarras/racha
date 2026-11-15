package io.github.roberbarras.racha.data

import io.github.roberbarras.racha.application.browse.UpdateItemProgress
import io.github.roberbarras.racha.application.library.EnsureBuiltInCategories
import io.github.roberbarras.racha.application.resource.ResourceContent
import io.github.roberbarras.racha.application.resource.ResourceViewer
import io.github.roberbarras.racha.application.shared.Clock
import io.github.roberbarras.racha.data.Fixtures.root
import io.github.roberbarras.racha.domain.library.CategoryKey
import io.github.roberbarras.racha.domain.library.CategoryRoot
import io.github.roberbarras.racha.domain.library.ContentId
import io.github.roberbarras.racha.domain.library.ElementKind
import io.github.roberbarras.racha.domain.library.PlayableElement
import io.github.roberbarras.racha.domain.library.RelativePath
import io.github.roberbarras.racha.domain.library.ResourceType
import io.github.roberbarras.racha.domain.resource.TextSegment
import io.github.roberbarras.racha.files.NioLibrarySource
import io.github.roberbarras.racha.files.NioResourceFiles
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Spec §5 and criterion 6: resources as course elements, read from real files. */
class ResourceViewerTest {
    private var now = Instant.parse("2026-11-15T10:00:00Z")
    private val clock = Clock { now }
    private val db = Fixtures.newDatabase()
    private val dir: Path = Files.createTempDirectory("racha-resources")
    private val course = Files.createDirectories(dir.resolve("AWS Course/01 - Introduction"))
    private val item = Fixtures.item("AWS Course")
    private val section = Fixtures.section(item, "01 - Introduction", 1)
    private val lesson = Fixtures.video(item, section, "001 Course Introduction.mp4", 1)

    private fun resource(file: String, type: ResourceType, order: Int, archived: Boolean = false): PlayableElement {
        val path = RelativePath.of("AWS Course/01 - Introduction/$file")
        return lesson.copy(
            id = ContentId.forResource(CategoryKey.COURSES, root, path), kind = ElementKind.RESOURCE, resourceType = type,
            title = file.substringBeforeLast('.'), relativePath = path, order = order, archived = archived,
        )
    }

    private val html = resource("002 Troubleshooting.html", ResourceType.HTML, 2)
    private val link = resource("003 Material.url", ResourceType.URL, 3)
    private val text = resource("004 Notes.txt", ResourceType.TEXT, 4)
    private val pdf = resource("005 Slides.pdf", ResourceType.PDF, 5)
    private val gone = resource("006 Old.html", ResourceType.HTML, 6, archived = true)

    private fun viewer() = ResourceViewer(
        db.library, db.categories, NioResourceFiles(), db.progress, db.history, UpdateItemProgress(db.library, db.progress), clock,
    )

    private fun seed() = runBlocking {
        Files.writeString(course.resolve("002 Troubleshooting.html"), "<h1>Hi</h1>")
        Files.writeString(course.resolve("003 Material.url"), "[InternetShortcut]\r\nURL=https://example.com/material\r\n")
        Files.write(course.resolve("004 Notes.txt"), "Configuración: ver https://aws.amazon.com/iam.".toByteArray(charset("windows-1252")))
        Files.write(course.resolve("005 Slides.pdf"), "%PDF-1.4".toByteArray())
        EnsureBuiltInCategories(db.categories, clock)()
        db.categories.upsertRoot(CategoryRoot(root, CategoryKey.COURSES, NioLibrarySource.locationOf(dir), 0))
        db.library.upsertItem(item)
        db.library.upsertSection(section)
        listOf(lesson, html, link, text, pdf, gone).forEach { db.library.upsertElement(it) }
    }

    @AfterTest
    fun cleanUp() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun criterion6HtmlIsServedFromItsCourseFolder() = runBlocking {
        seed()
        val viewer = viewer().apply { open(html.id) }
        val content = assertIs<ResourceContent.Html>(viewer.state.value.content)
        assertEquals(dir.resolve("AWS Course").toString(), content.folder)
        assertEquals("01 - Introduction/002 Troubleshooting.html", content.page.value)
        assertEquals(lesson.id, viewer.state.value.previous?.id, "a resource is a course element with neighbours")
        assertEquals(link.id, viewer.state.value.next?.id)
    }

    @Test
    fun shortcutsTextAndPdf() = runBlocking {
        seed()
        assertEquals(ResourceContent.Link("https://example.com/material"), viewer().apply { open(link.id) }.state.value.content)

        val notes = assertIs<ResourceContent.Text>(viewer().apply { open(text.id) }.state.value.content)
        assertEquals(TextSegment.Plain("Configuración: ver "), notes.segments.first(), "Windows-1252 text is decoded")
        assertEquals(TextSegment.Link("https://aws.amazon.com/iam", "https://aws.amazon.com/iam"), notes.segments[1])

        assertEquals(ResourceContent.Pdf(course.resolve("005 Slides.pdf").toString()), viewer().apply { open(pdf.id) }.state.value.content)
        assertEquals(ResourceContent.Missing, viewer().apply { open(gone.id) }.state.value.content)
    }

    @Test
    fun closingMarksWatchedAndARealVisitGoesToHistory() = runBlocking {
        seed()
        val viewer = viewer().apply { open(html.id) }
        now += 30.seconds
        viewer.close()
        viewer.close() // idempotent

        assertTrue(db.progress.get(html.id)!!.completed)
        assertTrue(db.library.item(item.id)!!.progressPct > 0, "the course progress counts the resource")
        assertEquals(listOf(html.id), db.history.observeRecent(10).first().map { it.elementId })
    }

    @Test
    fun aGlanceIsWatchedButNotHistoryAndMissingFilesStayUnwatched() = runBlocking {
        seed()
        viewer().apply { open(link.id); now += 2.seconds; close() }
        assertTrue(db.progress.get(link.id)!!.completed)
        assertTrue(db.history.observeRecent(10).first().isEmpty())

        viewer().apply { open(gone.id); close() }
        assertEquals(null, db.progress.get(gone.id))
    }

    @Test
    fun explicitMarksToggle() = runBlocking {
        seed()
        val viewer = viewer().apply { open(pdf.id) }
        viewer.setWatched(true)
        assertTrue(viewer.state.value.watched)
        viewer.setWatched(false)
        assertEquals(false, db.progress.get(pdf.id)!!.completed)
    }
}
