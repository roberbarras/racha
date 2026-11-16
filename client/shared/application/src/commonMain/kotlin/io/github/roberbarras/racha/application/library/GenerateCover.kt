package io.github.roberbarras.racha.application.library

import io.github.roberbarras.racha.application.preferences.Preferences
import io.github.roberbarras.racha.application.shared.AppLog
import io.github.roberbarras.racha.application.shared.SecretStore
import io.github.roberbarras.racha.application.sync.RemoteError
import io.github.roberbarras.racha.application.sync.reportCode
import io.github.roberbarras.racha.domain.library.ContentId
import io.github.roberbarras.racha.domain.preferences.Settings

class GeneratedImage(val bytes: ByteArray, val extension: String)

/** Outbound port: an image-generation API (spec §3: e.g. Pollinations, Hugging Face Inference). */
interface ImageGenerator {
    /** @throws RemoteError when the service fails or does not return an image. */
    suspend fun generate(endpoint: String, apiKey: String?, prompt: String): GeneratedImage
}

/** Outbound port: where generated covers are kept (app data, not a cache: they cost a request to make). */
interface CoverFiles {
    /** Saves the cover of [item] and returns its location for [io.github.roberbarras.racha.domain.library.LibraryItem.posterPath]. */
    suspend fun save(item: ContentId, image: GeneratedImage): String
}

sealed interface CoverResult {
    data object Saved : CoverResult
    data object NotConfigured : CoverResult
    data class Failed(val code: String) : CoverResult
}

/**
 * "Generate a cover with AI" (spec §3, optional; §9 "Escaneo: uso de IA de imágenes"): opt-in, with the user's endpoint,
 * key (kept in the platform's protected storage) and base prompt. Only the course/series title is sent. Folder
 * artwork still wins at the next scan; a generated cover replaces a frame thumbnail.
 */
class GenerateCover(
    private val library: LibraryRepository,
    private val preferences: Preferences,
    private val apiKey: SecretStore,
    private val generator: ImageGenerator,
    private val files: CoverFiles,
) {
    suspend fun available(): Boolean =
        preferences.get(Settings.AiCoverEnabled) && preferences.get(Settings.AiCoverEndpoint).isNotBlank()

    suspend operator fun invoke(itemId: ContentId): CoverResult {
        if (!available()) return CoverResult.NotConfigured
        val item = library.item(itemId) ?: return CoverResult.NotConfigured
        val prompt = prompt(preferences.get(Settings.AiCoverPrompt), item.displayTitle)
        return try {
            val image = generator.generate(preferences.get(Settings.AiCoverEndpoint).trim(), apiKey.read()?.takeIf { it.isNotBlank() }, prompt)
            val location = files.save(item.id, image)
            library.item(itemId)?.let { library.upsertItem(it.copy(posterPath = location)) }
            CoverResult.Saved
        } catch (e: RemoteError) {
            AppLog.warn("cover", "generation failed for ${item.id}", e)
            CoverResult.Failed(e.reportCode())
        }
    }

    companion object {
        const val DEFAULT_PROMPT = "Minimal, elegant poster illustration for «{title}». Flat shapes, soft colours, no text, no letters."

        /** The user's base prompt with `{title}`; without the placeholder, the title is appended. */
        fun prompt(base: String, title: String): String {
            val template = base.trim().ifEmpty { DEFAULT_PROMPT }
            return if ("{title}" in template) template.replace("{title}", title) else "$template — $title"
        }
    }
}
