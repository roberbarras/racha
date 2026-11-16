package io.github.roberbarras.racha.data

import io.github.roberbarras.racha.application.browse.SearchLibrary
import io.github.roberbarras.racha.application.library.EnsureBuiltInCategories
import io.github.roberbarras.racha.application.library.search.HtmlText
import io.github.roberbarras.racha.application.library.search.IndexResourceText
import io.github.roberbarras.racha.application.resource.ResourceFiles
import io.github.roberbarras.racha.application.resource.ResourceTextFile
import io.github.roberbarras.racha.application.shared.Clock
import io.github.roberbarras.racha.data.Fixtures.item
import io.github.roberbarras.racha.data.Fixtures.section
import io.github.roberbarras.racha.data.Fixtures.video
import io.github.roberbarras.racha.domain.library.CategoryKey
import io.github.roberbarras.racha.domain.library.CategoryRoot
import io.github.roberbarras.racha.domain.library.ContentId
import io.github.roberbarras.racha.domain.library.ElementKind
import io.github.roberbarras.racha.domain.library.MediaInfo
import io.github.roberbarras.racha.domain.library.RelativePath
import io.github.roberbarras.racha.domain.library.ResourceType
import io.github.roberbarras.racha.domain.library.SearchText
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Global search (spec §2.2): titles and resource content, ignoring case and accents. */
class SearchTest {
    private val now = Instant.parse("2026-11-16T10:00:00Z")
    private val db = Fixtures.newDatabase()
    private val files = mutableMapOf<String, String>()
    private var reads = 0
    private val resourceFiles = object : ResourceFiles {
        override suspend fun readText(location: String, path: RelativePath, maxBytes: Int): ResourceTextFile? =
            files[path.value]?.let { reads++; ResourceTextFile(it, false) }
        override fun localPath(location: String, path: RelativePath): String? = null
    }
    private val course = item("Introducción a la Programación")
    private val intro = section(course, "01 - Básicos", 1)
    private val lesson = video(course, intro, "001 Árboles y Grafos.mp4", 1)
    private val page = lesson.copy(
        id = ContentId.forResource(CategoryKey.COURSES, Fixtures.root, course.relativePath.resolve("01 - Básicos/002 Seguridad.html")),
        kind = ElementKind.RESOURCE, resourceType = ResourceType.HTML, title = "002 Seguridad",
        relativePath = course.relativePath.resolve("01 - Básicos/002 Seguridad.html"), media = MediaInfo.UNKNOWN, order = 2,
    )
    private val search = SearchLibrary(db.library, db.resourceText)
    private val index = IndexResourceText(db.categories, db.library, resourceFiles, db.resourceText)

    private suspend fun seed() {
        EnsureBuiltInCategories(db.categories, Clock { now })()
        db.categories.upsertRoot(CategoryRoot(Fixtures.root, CategoryKey.COURSES, "file:///cursos", 0))
        db.library.upsertItem(course)
        db.library.upsertSection(intro)
        db.library.upsertElement(lesson)
        db.library.upsertElement(page)
        files[page.relativePath.value] = """
            <html><head><title>Seguridad</title><style>.x { color: red }</style><script>var secreto = "contraseña";</script></head>
            <body><h1>Política de contraseñas</h1><p>Usa contraseñas largas &amp; únicas para cada cuenta. Activa la
            autenticación en dos pasos&nbsp;siempre que puedas.</p><!-- nota interna: olvidar --></body></html>
        """.trimIndent()
    }

    @Test
    fun titlesMatchWithoutCaseOrAccents() = runBlocking<Unit> {
        seed()
        assertEquals(listOf(course.id), search("INTRODUCCION").items.map { it.id })
        assertEquals(listOf(course.id), search("programación").items.map { it.id })
        assertEquals(listOf(lesson.id), search("arboles").elements.map { it.element.id })
        assertTrue(search("x").isEmpty, "one character is not a search")
    }

    @Test
    fun resourceContentIsFoundWithTheWordsAroundIt() = runBlocking<Unit> {
        seed()
        assertEquals(1, index())
        val result = search("politica de contrasenas")
        val match = result.inContent.single()
        assertEquals(page.id, match.element.element.id)
        assertTrue("Política de contraseñas" in match.snippet, match.snippet)
        assertTrue(search("dos pasos").inContent.isNotEmpty(), "&nbsp; is a space")
        assertTrue(search("secreto").inContent.isEmpty(), "scripts are not content")
        assertTrue(search("nota interna").inContent.isEmpty(), "comments are not content")
        assertTrue(search("color: red").inContent.isEmpty(), "styles are not content")
        assertTrue(search("seguridad").inContent.isEmpty(), "found by title already: not repeated in content")

        assertEquals(0, index(), "unchanged files are not read again")
        assertEquals(1, reads)
        db.library.upsertElement(page.copy(fileModifiedAt = now + 5.minutes))
        files[page.relativePath.value] = "<p>Rotación de claves</p>"
        assertEquals(1, index())
        assertTrue(search("rotacion").inContent.isNotEmpty())
        assertTrue(search("politica").inContent.isEmpty())
    }

    @Test
    fun foldingKeepsPositionsAndHtmlTextDecodes() {
        assertEquals("nino jose aeiou", SearchText.fold("NIÑO José ÁÉÍÓÚ"))
        assertEquals("NIÑO José".length, SearchText.fold("NIÑO José").length)
        assertEquals("a & b < c ¿qué? 😀", HtmlText.extract("<b>a</b> &amp; b &lt; c &iquest;qu&eacute;? &#x1F600;"))
        assertEquals("one\ntwo", HtmlText.extract("<p>one</p>  <p>two</p>"))
    }
}
