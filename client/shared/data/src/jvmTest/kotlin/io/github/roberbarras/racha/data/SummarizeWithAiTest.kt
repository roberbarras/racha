package io.github.roberbarras.racha.data

import io.github.roberbarras.racha.application.ai.Attempt
import io.github.roberbarras.racha.application.ai.ChatClient
import io.github.roberbarras.racha.application.ai.ChatMessage
import io.github.roberbarras.racha.application.ai.ProviderKeys
import io.github.roberbarras.racha.application.ai.SummarizeWithAi
import io.github.roberbarras.racha.application.ai.SummaryOutcome
import io.github.roberbarras.racha.application.library.EnsureBuiltInCategories
import io.github.roberbarras.racha.application.preferences.Preferences
import io.github.roberbarras.racha.application.shared.Clock
import io.github.roberbarras.racha.application.shared.SecretStore
import io.github.roberbarras.racha.application.sync.RemoteError
import io.github.roberbarras.racha.application.sync.SyncRecord
import io.github.roberbarras.racha.data.Fixtures.root
import io.github.roberbarras.racha.domain.ai.SummaryProvider
import io.github.roberbarras.racha.domain.ai.SummaryTarget
import io.github.roberbarras.racha.domain.library.CategoryKey
import io.github.roberbarras.racha.domain.library.CategoryRoot
import io.github.roberbarras.racha.domain.library.ContentId
import io.github.roberbarras.racha.domain.library.LanguageCode
import io.github.roberbarras.racha.domain.library.Subtitle
import io.github.roberbarras.racha.domain.preferences.ConflictPolicy
import io.github.roberbarras.racha.domain.preferences.Settings
import io.github.roberbarras.racha.files.NioLibrarySource
import io.github.roberbarras.racha.files.NioResourceFiles
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/** "Resumen y claves" (H17): subtitles to a summary, providers in the user's order, kept and synced. */
class SummarizeWithAiTest {
    private val now = Instant.parse("2026-11-17T20:00:00Z")
    private val clock = Clock { now }
    private val db = Fixtures.newDatabase()
    private val preferences = Preferences(db.settings, clock)
    private val dir: Path = Files.createTempDirectory("racha-summaries")
    private val item = Fixtures.item("AWS Course")
    private val section = Fixtures.section(item, "01 - Introduction", 1)
    private val short = Fixtures.video(item, section, "001 Welcome.mp4", 1)
    private val long = Fixtures.video(item, section, "002 IAM in depth.mp4", 2)
    private val silent = Fixtures.video(item, section, "003 Demo.mp4", 3)

    /** Who was asked, in order, and what; [failing] answers like a provider with no free quota left. */
    private inner class FakeChat(private val failing: Map<String, Int> = emptyMap()) : ChatClient {
        val calls = mutableListOf<Pair<String, String>>()

        override suspend fun complete(baseUrl: String, apiKey: String?, model: String, messages: List<ChatMessage>, maxTokens: Int): String {
            calls += baseUrl to messages.last().content
            failing[baseUrl]?.let { throw RemoteError.Rejected(it, null, "nope") }
            val prompt = messages.last().content
            return when {
                prompt.startsWith("Toma notas") -> "- nota de ${prompt.substringAfter("parte ").substringBefore(" de")}"
                prompt.startsWith("Con estos") -> "## Resumen\nCombinado (${prompt.lines().count { it.startsWith("### ") }} partes)"
                else -> "## Resumen\nResumen de ${prompt.substringAfter("«").substringBefore("»")}"
            }
        }

        override suspend fun models(baseUrl: String, apiKey: String?) = listOf("b-model", "a-model")
    }

    private val keys = mutableMapOf("groq" to "gsk-test", "gemini" to "AIza-test")
    private val providerKeys = ProviderKeys { id ->
        object : SecretStore {
            override suspend fun read() = keys[id]
            override suspend fun write(value: String) { keys[id] = value }
            override suspend fun clear() { keys.remove(id) }
        }
    }

    private fun useCase(chat: ChatClient) =
        SummarizeWithAi(db.library, db.categories, NioResourceFiles(), db.summaries, chat, preferences, providerKeys, clock) { "es" }

    private fun srt(vararg lines: String) = lines.mapIndexed { i, l -> "${i + 1}\n00:00:0$i,000 --> 00:00:0${i + 1},000\n$l\n" }.joinToString("\n")

    private fun seed(providers: List<SummaryProvider>) = runBlocking {
        val folder = Files.createDirectories(dir.resolve("AWS Course/01 - Introduction"))
        Files.writeString(folder.resolve("001 Welcome.es.srt"), srt("Hola y bienvenidos.", "Hola y bienvenidos.", "<i>Empezamos</i> con IAM."))
        Files.writeString(folder.resolve("002 IAM in depth.en.srt"), srt(*Array(3_000) { "Policies are JSON documents number $it." }))
        EnsureBuiltInCategories(db.categories, clock)()
        db.categories.upsertRoot(CategoryRoot(root, CategoryKey.COURSES, NioLibrarySource.locationOf(dir), 0))
        db.library.upsertItem(item)
        db.library.upsertSection(section)
        listOf(short, long, silent).forEach { db.library.upsertElement(it) }
        subtitle(short, "001 Welcome.es.srt", "es")
        subtitle(long, "002 IAM in depth.en.srt", "en")
        preferences.set(Settings.SummaryProviders, SummaryProvider.encode(providers))
    }

    private suspend fun subtitle(element: io.github.roberbarras.racha.domain.library.PlayableElement, file: String, language: String) {
        val path = section.let { item.relativePath.resolve(it.title).resolve(file) }
        db.library.upsertSubtitle(Subtitle(ContentId.forSubtitle(CategoryKey.COURSES, root, path), element.id, LanguageCode(language), path, now))
    }

    private val mistral = SummaryProvider("p1", "mistral", "Mistral", "https://mistral.test/v1", "mistral-small-latest")
    private val groq = SummaryProvider("groq", "groq", "Groq", "https://groq.test/v1", "llama")
    private val gemini = SummaryProvider("gemini", "gemini", "Google Gemini", "https://gemini.test/v1", "gemini-2.5-flash")
    private val off = SummaryProvider("p4", "ollama", "Ollama", "http://localhost:11434/v1", "llama3.2", enabled = false)

    @AfterTest
    fun cleanUp() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun theNextProviderTakesOverWhenOneHasNoQuotaAndAnswersFirstForTheRestOfTheJob() = runBlocking<Unit> {
        seed(listOf(off, mistral, groq, gemini))
        val chat = FakeChat(failing = mapOf(groq.baseUrl to 429))
        val ai = useCase(chat)

        val done = assertIs<SummaryOutcome.Done>(ai.lesson(short.id))
        assertEquals("Google Gemini", done.summary.provider)
        assertEquals("## Resumen\nResumen de 001 Welcome", done.summary.markdown)
        assertEquals(listOf(groq.baseUrl, gemini.baseUrl), chat.calls.map { it.first }, "disabled and keyless providers are not asked")
        assertTrue("Hola y bienvenidos. Empezamos con IAM." in chat.calls.last().second, "the transcript, without timings, tags or repeats")

        // Kept: asking again does not call anyone.
        assertIs<SummaryOutcome.Done>(ai.lesson(short.id))
        assertEquals(2, chat.calls.size)
        assertEquals(SummaryTarget.ELEMENT, db.summaries.get(short.id)?.target)

        // A long lesson in parts, then combined: once Gemini answers the first part, it is asked first for the rest.
        chat.calls.clear()
        val longOne = assertIs<SummaryOutcome.Done>(ai.lesson(long.id))
        assertTrue(chat.calls.size > 3, "several parts and the combination")
        assertEquals(listOf(groq.baseUrl), chat.calls.map { it.first }.filter { it != gemini.baseUrl })
        assertTrue(longOne.summary.markdown.startsWith("## Resumen\nCombinado"))
    }

    @Test
    fun aSectionIsWrittenFromItsLessonsAndLessonsWithoutSubtitlesCannotBeSummarised() = runBlocking<Unit> {
        seed(listOf(gemini))
        val chat = FakeChat()
        val ai = useCase(chat)
        assertTrue(ai.canSummarize(short.id))
        assertFalse(ai.canSummarize(silent.id), "the button is disabled without .srt")
        assertIs<SummaryOutcome.NoSubtitles>(ai.lesson(silent.id))

        val done = assertIs<SummaryOutcome.Done>(ai.section(item.id, section.id))
        assertEquals(SummaryTarget.SECTION, done.summary.target)
        assertEquals("## Resumen\nCombinado (2 partes)", done.summary.markdown, "the two lessons with subtitles")
        assertTrue(db.summaries.get(short.id) != null && db.summaries.get(long.id) != null, "the lessons' summaries are kept too")
        assertEquals(listOf("a-model", "b-model"), ai.models(gemini))
    }

    @Test
    fun everyProviderFailingSaysWhyAndNothingSetUpSaysSo() = runBlocking<Unit> {
        seed(listOf(groq, gemini))
        keys.remove("gemini")
        val failed = assertIs<SummaryOutcome.Failed>(useCase(FakeChat(failing = mapOf(groq.baseUrl to 401))).lesson(short.id))
        assertEquals(listOf(Attempt("Groq", "key"), Attempt("Google Gemini", "no_key")), failed.attempts)
        assertEquals(null, db.summaries.get(short.id))
        assertEquals("quota", useCase(FakeChat(failing = mapOf(groq.baseUrl to 429))).test(groq))
        assertEquals(null, useCase(FakeChat()).test(groq))

        preferences.set(Settings.SummaryProviders, "")
        assertIs<SummaryOutcome.NotConfigured>(useCase(FakeChat()).lesson(short.id))
    }

    @Test
    fun summariesTravelWithSync() = runBlocking<Unit> {
        seed(listOf(gemini))
        db.sync.startRecording()
        assertIs<SummaryOutcome.Done>(useCase(FakeChat()).lesson(short.id))
        val change = db.sync.pending(100).map { it.change }.single { it.entity == "summary" }
        assertEquals(short.id.value, change.id)
        assertEquals("element", change.data["target"]!!.jsonPrimitive.content)

        val other = Fixtures.newDatabase()
        other.sync.applyRemote(listOf(SyncRecord("summary", change.id, change.updatedAt, false, 1, change.data)), ConflictPolicy.LAST_WRITE_WINS)
        assertEquals(db.summaries.get(short.id), other.summaries.get(short.id))
        other.sync.applyRemote(listOf(SyncRecord("summary", "not-an-id", now, false, 2, change.data)), ConflictPolicy.LAST_WRITE_WINS)
        other.sync.applyRemote(listOf(SyncRecord("summary", change.id, now.plus(kotlin.time.Duration.parse("1s")), true, 3, change.data)),
            ConflictPolicy.LAST_WRITE_WINS)
        assertEquals(null, other.summaries.get(short.id))
    }
}
