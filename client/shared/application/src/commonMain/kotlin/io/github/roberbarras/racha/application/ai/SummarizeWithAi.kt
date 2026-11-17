package io.github.roberbarras.racha.application.ai

import io.github.roberbarras.racha.application.library.CategoryRepository
import io.github.roberbarras.racha.application.library.LibraryRepository
import io.github.roberbarras.racha.application.preferences.Preferences
import io.github.roberbarras.racha.application.resource.ResourceFiles
import io.github.roberbarras.racha.application.shared.AppLog
import io.github.roberbarras.racha.application.shared.Clock
import io.github.roberbarras.racha.application.sync.RemoteError
import io.github.roberbarras.racha.domain.ai.ProviderPresets
import io.github.roberbarras.racha.domain.ai.Summary
import io.github.roberbarras.racha.domain.ai.SummaryPrompts
import io.github.roberbarras.racha.domain.ai.SummaryProvider
import io.github.roberbarras.racha.domain.ai.SummaryTarget
import io.github.roberbarras.racha.domain.ai.Transcript
import io.github.roberbarras.racha.domain.library.ContentId
import io.github.roberbarras.racha.domain.library.PlayableElement
import io.github.roberbarras.racha.domain.preferences.Settings
import kotlin.coroutines.cancellation.CancellationException

/** One provider that did not answer, and why (`quota`, `key`, `payment`, `model`, `too_long`, `server`, `unreachable`…). */
data class Attempt(val provider: String, val reason: String)

sealed interface SummaryOutcome {
    data class Done(val summary: Summary) : SummaryOutcome
    /** No provider is set up. */
    data object NotConfigured : SummaryOutcome
    /** Nothing to summarise from: no `.srt` (the button is disabled then). */
    data object NoSubtitles : SummaryOutcome
    /** The folder is not on this device. */
    data object Unavailable : SummaryOutcome
    /** Every provider failed, in order. */
    data class Failed(val attempts: List<Attempt>) : SummaryOutcome
}

/**
 * "Resumen y claves" (H17): a lesson's subtitles, or a whole section, summarised by an AI provider. Providers are tried
 * in the user's order and the next one takes over when one fails (no free quota left, wrong key, model gone, down); once
 * one answers, it is asked first for the rest of the job. Long transcripts are summarised in parts and combined; a
 * section is made from its lessons' summaries (written first when missing, and kept). The summary is in the app's
 * language and is stored with the app's data (synced, exportable).
 */
class SummarizeWithAi(
    private val library: LibraryRepository,
    private val categories: CategoryRepository,
    private val files: ResourceFiles,
    private val summaries: SummaryRepository,
    private val chat: ChatClient,
    private val preferences: Preferences,
    private val keys: ProviderKeys,
    private val clock: Clock,
    private val language: () -> String,
) {
    suspend fun providers(): List<SummaryProvider> = SummaryProvider.decode(preferences.get(Settings.SummaryProviders))

    suspend fun configured(): Boolean = providers().any { it.usable }

    /** Whether the lesson can be summarised here: a provider is set up and it has subtitles. */
    suspend fun canSummarize(element: ContentId): Boolean = configured() && library.subtitles(element).isNotEmpty()

    suspend fun lesson(element: ContentId, regenerate: Boolean = false): SummaryOutcome {
        if (!configured()) return SummaryOutcome.NotConfigured
        if (!regenerate) summaries.get(element)?.let { return SummaryOutcome.Done(it) }
        val e = library.element(element) ?: return SummaryOutcome.Unavailable
        return Job().lesson(e)
    }

    /** The section's lessons with subtitles, each summarised (or reused), then combined. */
    suspend fun section(item: ContentId, section: ContentId, regenerate: Boolean = false): SummaryOutcome {
        if (!configured()) return SummaryOutcome.NotConfigured
        if (!regenerate) summaries.get(section)?.let { return SummaryOutcome.Done(it) }
        val s = library.sections(item).firstOrNull { it.id == section } ?: return SummaryOutcome.Unavailable
        val course = library.item(item)?.displayTitle ?: ""
        val lessons = library.elements(item).filter { it.sectionId == section && !it.archived && library.subtitles(it.id).isNotEmpty() }
        if (lessons.isEmpty()) return SummaryOutcome.NoSubtitles
        val job = Job()
        val parts = lessons.map { lesson ->
            val done = summaries.get(lesson.id)?.takeIf { !regenerate } ?: when (val outcome = job.lesson(lesson)) {
                is SummaryOutcome.Done -> outcome.summary
                SummaryOutcome.NoSubtitles -> return@map null // unreadable file: the others still count
                else -> return outcome
            }
            lesson.displayTitle to done.markdown
        }.filterNotNull()
        if (parts.isEmpty()) return SummaryOutcome.NoSubtitles
        val title = s.displayTitle.ifBlank { course }
        val answer = job.combine(title, course, parts) ?: return SummaryOutcome.Failed(job.attempts)
        return SummaryOutcome.Done(store(section, SummaryTarget.SECTION, answer))
    }

    /** A tiny request to check a provider (Settings → "Probar"): null if it answered, else why not. */
    suspend fun test(provider: SummaryProvider): String? = try {
        chat.complete(provider.baseUrl, key(provider), provider.model, listOf(ChatMessage("user", "Reply with: OK")), 5)
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: RemoteError) {
        reason(e)
    }

    suspend fun models(provider: SummaryProvider): List<String> = chat.models(provider.baseUrl, key(provider)).sorted()

    private suspend fun key(provider: SummaryProvider): String? = keys.of(provider.id).read()?.takeIf { it.isNotBlank() }

    private suspend fun store(target: ContentId, kind: SummaryTarget, answer: Answer): Summary {
        val summary = Summary(target, kind, answer.text.trim(), answer.provider.name, answer.provider.model, language(), clock.now())
        summaries.save(summary)
        return summary
    }

    private data class Answer(val text: String, val provider: SummaryProvider)

    /** One summarising job: remembers the attempts and which provider answered, to ask it first next time. */
    private inner class Job {
        val attempts = mutableListOf<Attempt>()
        private var preferred: String? = null

        suspend fun lesson(element: PlayableElement): SummaryOutcome {
            val item = library.item(element.libraryItemId) ?: return SummaryOutcome.Unavailable
            val location = categories.roots(item.categoryKey).firstOrNull { it.key == item.rootKey }?.location ?: return SummaryOutcome.Unavailable
            val lang = language()
            val subtitles = library.subtitles(element.id)
            val subtitle = subtitles.firstOrNull { it.language.value.equals(lang, ignoreCase = true) }
                ?: subtitles.firstOrNull { it.language.value.substringBefore('-').equals(lang.substringBefore('-'), ignoreCase = true) }
                ?: subtitles.firstOrNull()
                ?: return SummaryOutcome.NoSubtitles
            val text = files.readText(location, subtitle.relativePath, MAX_SRT_BYTES)?.text ?: return SummaryOutcome.NoSubtitles
            val transcript = Transcript.fromSrt(text)
            if (transcript.isBlank()) return SummaryOutcome.NoSubtitles
            val chunks = Transcript.chunks(transcript, CHUNK_CHARS)
            val answer = if (chunks.size == 1) {
                ask(SummaryPrompts.lesson(lang, element.displayTitle, item.displayTitle, transcript), LESSON_TOKENS)
            } else {
                val notes = chunks.mapIndexed { i, chunk ->
                    val part = ask(SummaryPrompts.part(lang, element.displayTitle, i + 1, chunks.size, chunk), PART_TOKENS)
                        ?: return SummaryOutcome.Failed(attempts)
                    "${i + 1}/${chunks.size}" to part.text
                }
                combine(element.displayTitle, item.displayTitle, notes)
            } ?: return SummaryOutcome.Failed(attempts)
            return SummaryOutcome.Done(store(element.id, SummaryTarget.ELEMENT, answer))
        }

        /** Combines parts; too many for one request, they are combined in groups first. */
        suspend fun combine(title: String, course: String, parts: List<Pair<String, String>>): Answer? {
            var current = parts
            while (current.sumOf { it.second.length } > CHUNK_CHARS && current.size > 1) {
                val groups = mutableListOf<MutableList<Pair<String, String>>>(mutableListOf())
                current.forEach { part ->
                    if (groups.last().isNotEmpty() && groups.last().sumOf { it.second.length } + part.second.length > CHUNK_CHARS) groups.add(mutableListOf())
                    groups.last() += part
                }
                if (groups.size == current.size) break // every part alone is already too big: let the provider decide
                current = groups.map { group ->
                    val answer = ask(SummaryPrompts.combine(language(), title, course, group), COMBINE_TOKENS) ?: return null
                    group.first().first + " – " + group.last().first to answer.text
                }
            }
            return ask(SummaryPrompts.combine(language(), title, course, current), COMBINE_TOKENS)
        }

        suspend fun ask(prompt: String, maxTokens: Int): Answer? {
            val all = providers().filter { it.usable }
            val ordered = all.sortedBy { if (it.id == preferred) 0 else 1 } // stable: the user's order otherwise
            val messages = listOf(ChatMessage("system", SummaryPrompts.system(language())), ChatMessage("user", prompt))
            for (provider in ordered) {
                val key = key(provider)
                if (key == null && ProviderPresets.byId(provider.presetId)?.needsKey == true) {
                    attempts += Attempt(provider.name, "no_key")
                    continue
                }
                try {
                    val text = chat.complete(provider.baseUrl, key, provider.model, messages, maxTokens)
                    if (text.isBlank()) {
                        attempts += Attempt(provider.name, "empty")
                        continue
                    }
                    preferred = provider.id
                    return Answer(text, provider)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: RemoteError) {
                    AppLog.warn("ai", "${provider.name} failed: ${reason(e)}", e)
                    attempts += Attempt(provider.name, reason(e))
                }
            }
            return null
        }
    }

    companion object {
        /** About 6 000 tokens: fits the free tiers' smallest per-request limits. */
        const val CHUNK_CHARS = 24_000
        const val MAX_SRT_BYTES = 2 * 1024 * 1024
        const val LESSON_TOKENS = 1_800
        const val PART_TOKENS = 900
        const val COMBINE_TOKENS = 2_000

        fun reason(e: RemoteError): String = when (e) {
            is RemoteError.Unreachable -> "unreachable"
            is RemoteError.Unauthorized -> "key"
            is RemoteError.Rejected -> when (e.status) {
                429 -> "quota"
                401, 403 -> "key"
                402 -> "payment"
                404 -> "model"
                413 -> "too_long"
                in 500..599 -> "server"
                else -> "rejected:${e.status}"
            }
        }
    }
}
