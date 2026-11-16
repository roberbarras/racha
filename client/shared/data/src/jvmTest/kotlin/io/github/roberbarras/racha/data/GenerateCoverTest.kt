package io.github.roberbarras.racha.data

import io.github.roberbarras.racha.application.library.CoverResult
import io.github.roberbarras.racha.application.library.EnsureBuiltInCategories
import io.github.roberbarras.racha.application.library.GenerateCover
import io.github.roberbarras.racha.application.library.GeneratedImage
import io.github.roberbarras.racha.application.library.ImageGenerator
import io.github.roberbarras.racha.application.preferences.Preferences
import io.github.roberbarras.racha.application.shared.Clock
import io.github.roberbarras.racha.application.shared.SecretStore
import io.github.roberbarras.racha.application.sync.RemoteError
import io.github.roberbarras.racha.domain.preferences.Settings
import io.github.roberbarras.racha.files.DirectoryCoverFiles
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/** AI covers (spec §3, optional): only when configured; the key from protected storage; only the title is sent. */
class GenerateCoverTest {
    private val clock = Clock { Instant.parse("2026-11-16T10:00:00Z") }
    private val db = Fixtures.newDatabase()
    private val preferences = Preferences(db.settings, clock)
    private val dir = Files.createTempDirectory("racha-covers")
    private val key = object : SecretStore {
        var value: String? = "k-123"
        override suspend fun read() = value
        override suspend fun write(value: String) { this.value = value }
        override suspend fun clear() { value = null }
    }
    private var asked: Triple<String, String?, String>? = null
    private var fail: RemoteError? = null
    private val generator = object : ImageGenerator {
        override suspend fun generate(endpoint: String, apiKey: String?, prompt: String): GeneratedImage {
            fail?.let { throw it }
            asked = Triple(endpoint, apiKey, prompt)
            return GeneratedImage(byteArrayOf(1, 2, 3), "png")
        }
    }
    private val cover = GenerateCover(db.library, preferences, key, generator, DirectoryCoverFiles(dir))
    private val course = Fixtures.item("AWS Course")

    @Test
    fun aConfiguredServiceGivesTheCourseACover() = runBlocking<Unit> {
        EnsureBuiltInCategories(db.categories, clock)()
        db.library.upsertItem(course)
        assertEquals(CoverResult.NotConfigured, cover(course.id), "off by default")

        preferences.set(Settings.AiCoverEnabled, true)
        preferences.set(Settings.AiCoverEndpoint, "https://image.example.com/prompt/{prompt}")
        preferences.set(Settings.AiCoverPrompt, "Watercolour poster of {title}, no text")
        assertEquals(CoverResult.Saved, cover(course.id))
        assertEquals(Triple("https://image.example.com/prompt/{prompt}", "k-123", "Watercolour poster of AWS Course, no text"), asked)
        val poster = db.library.item(course.id)!!.posterPath!!
        assertTrue(Files.exists(Path.of(java.net.URI(poster))))

        assertEquals(CoverResult.Saved, cover(course.id))
        assertEquals(1, dir.listDirectoryEntries().size, "a new cover replaces the old file")

        fail = RemoteError.Rejected(401, "unauthorized", null)
        assertEquals(CoverResult.Failed("rejected:401:unauthorized"), cover(course.id))
    }

    @Test
    fun aBasePromptWithoutPlaceholderGetsTheTitleAppended() {
        assertEquals("Retro poster — Series X", GenerateCover.prompt("Retro poster", "Series X"))
        assertTrue("«Series X»" in GenerateCover.prompt("", "Series X"))
    }
}
