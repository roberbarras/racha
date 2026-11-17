package io.github.roberbarras.racha.domain.ai

import io.github.roberbarras.racha.domain.library.ContentId
import kotlin.time.Instant

/** What a summary is about: one lesson or episode, or a whole section. */
enum class SummaryTarget { ELEMENT, SECTION }

/** A generated summary (H17), kept with the app's data and synced: written once, it is there on every device. */
data class Summary(
    val targetId: ContentId,
    val target: SummaryTarget,
    val markdown: String,
    /** Who wrote it: provider name and model, shown under it. */
    val provider: String,
    val model: String,
    val language: String,
    val updatedAt: Instant,
)

/**
 * Where summaries are written (H17). Every one speaks the OpenAI chat-completions protocol (Gemini through its
 * compatible endpoint), so adding a provider is adding a preset. [model] is only a starting point: the user picks it,
 * and the provider's model list can be loaded. [freeTier] says whether it can be used without paying.
 */
data class ProviderPreset(
    val id: String,
    val name: String,
    val baseUrl: String,
    val model: String,
    val needsKey: Boolean,
    val freeTier: Boolean,
    val keysUrl: String?,
)

object ProviderPresets {
    val all: List<ProviderPreset> = listOf(
        ProviderPreset("gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.5-flash", true, true,
            "https://aistudio.google.com/apikey"),
        ProviderPreset("groq", "Groq", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile", true, true, "https://console.groq.com/keys"),
        ProviderPreset("nvidia", "NVIDIA NIM", "https://integrate.api.nvidia.com/v1", "meta/llama-3.3-70b-instruct", true, true,
            "https://build.nvidia.com/settings/api-keys"),
        ProviderPreset("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "meta-llama/llama-3.3-70b-instruct:free", true, true,
            "https://openrouter.ai/settings/keys"),
        ProviderPreset("mistral", "Mistral", "https://api.mistral.ai/v1", "mistral-small-latest", true, true, "https://console.mistral.ai/api-keys"),
        ProviderPreset("cerebras", "Cerebras", "https://api.cerebras.ai/v1", "llama-3.3-70b", true, true, "https://cloud.cerebras.ai"),
        ProviderPreset("deepseek", "DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat", true, false, "https://platform.deepseek.com/api_keys"),
        ProviderPreset("ollama", "Ollama", "http://localhost:11434/v1", "llama3.2", false, true, null),
        ProviderPreset("custom", "OpenAI-compatible", "", "", true, false, null),
    )

    fun byId(id: String): ProviderPreset? = all.firstOrNull { it.id == id }
}

/** One provider the user set up; tried in list order, [enabled] ones only. The key lives in the keyring under [id]. */
data class SummaryProvider(val id: String, val presetId: String, val name: String, val baseUrl: String, val model: String, val enabled: Boolean = true) {
    val usable: Boolean get() = enabled && baseUrl.isNotBlank() && model.isNotBlank()

    companion object {
        /** One provider per line, fields separated by tabs (id, preset, name, URL, model, enabled): a plain setting value. */
        fun encode(list: List<SummaryProvider>): String = list.joinToString("\n") { p ->
            listOf(p.id, p.presetId, p.name, p.baseUrl, p.model, p.enabled.toString()).joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ') }
        }

        fun decode(raw: String): List<SummaryProvider> = raw.lines().filter { it.isNotBlank() }.mapNotNull { line ->
            val f = line.split('\t')
            if (f.size < 6) null else SummaryProvider(f[0], f[1], f[2], f[3], f[4], f[5].toBooleanStrictOrNull() ?: true)
        }.distinctBy { it.id }
    }
}

/** Subtitles to plain text a model can read: no numbers, timings or tags; repeated lines (rolling captions) once. */
object Transcript {
    private val timing = Regex("""^\d{1,2}:\d{2}:\d{2}[,.]\d{1,3}\s*-->\s*\d{1,2}:\d{2}:\d{2}[,.]\d{1,3}.*$""")
    private val tags = Regex("""<[^>]+>|\{\\[^}]*}""")

    fun fromSrt(srt: String): String {
        val lines = srt.replace("\r", "").removePrefix("﻿").lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.all(Char::isDigit) && !timing.matches(it) }
            .map { it.replace(tags, "").trim() }
            .filter { it.isNotEmpty() }
        val out = ArrayList<String>(lines.size)
        lines.forEach { if (out.lastOrNull() != it) out += it }
        return out.joinToString(" ").replace(Regex("\\s+"), " ").trim()
    }

    /** Pieces of at most [size] characters, cut at sentence ends where possible (long lessons, small free quotas). */
    fun chunks(text: String, size: Int): List<String> {
        if (text.length <= size) return listOf(text)
        val pieces = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            var end = minOf(text.length, start + size)
            if (end < text.length) {
                val cut = text.lastIndexOfAny(charArrayOf('.', '?', '!'), end - 1).takeIf { it > start + size / 2 }
                if (cut != null) end = cut + 1
            }
            pieces += text.substring(start, end).trim()
            start = end
        }
        return pieces.filter { it.isNotEmpty() }
    }
}

/** What is asked of the model, in the app's language. The answer is Markdown with fixed headings. */
object SummaryPrompts {
    fun system(language: String): String = if (spanish(language)) {
        "Eres un asistente de estudio. Escribes en español, en Markdown, con precisión y sin inventar nada que no esté en el texto."
    } else {
        "You are a study assistant. You write in English, in Markdown, accurately and without adding anything that is not in the text."
    }

    /** A lesson from its transcript. */
    fun lesson(language: String, title: String, course: String, transcript: String): String = if (spanish(language)) {
        """Resume la clase «$title» del curso «$course» a partir de su transcripción. Responde solo con este Markdown:

## Resumen
(un párrafo o dos con lo esencial)

## Claves
(5 a 10 viñetas con las ideas más importantes)

## Términos
(los conceptos o comandos que aparecen, cada uno con una explicación breve; omite la sección si no hay)

## Para repasar
(3 a 5 preguntas para comprobar que se ha entendido)

Transcripción:
$transcript"""
    } else {
        """Summarise the lesson "$title" of the course "$course" from its transcript. Answer only with this Markdown:

## Summary
(one or two paragraphs with the essentials)

## Key points
(5 to 10 bullets with the most important ideas)

## Terms
(the concepts or commands that appear, each with a short explanation; leave the section out if there are none)

## Review
(3 to 5 questions to check it was understood)

Transcript:
$transcript"""
    }

    /** Notes on one piece of a long transcript, to be combined afterwards. */
    fun part(language: String, title: String, index: Int, count: Int, text: String): String = if (spanish(language)) {
        "Toma notas detalladas (viñetas, en español) de la parte $index de $count de la transcripción de la clase «$title». Solo las notas.\n\n$text"
    } else {
        "Take detailed notes (bullets, in English) on part $index of $count of the transcript of the lesson \"$title\". Only the notes.\n\n$text"
    }

    /** A section from its lessons' summaries (or a long lesson from its notes): the same headings. */
    fun combine(language: String, title: String, course: String, parts: List<Pair<String, String>>): String {
        val body = parts.joinToString("\n\n") { (name, text) -> "### $name\n$text" }
        return if (spanish(language)) {
            """Con estos resúmenes, escribe el resumen de «$title» (del curso «$course»), con las mismas secciones: ## Resumen, ## Claves, ## Términos y ## Para repasar. Responde solo con el Markdown.

$body"""
        } else {
            """From these summaries, write the summary of "$title" (of the course "$course") with the same sections: ## Summary, ## Key points, ## Terms and ## Review. Answer only with the Markdown.

$body"""
        }
    }

    private fun spanish(language: String) = language.lowercase().startsWith("es")
}
