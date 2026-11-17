package io.github.roberbarras.racha.application.ai

import io.github.roberbarras.racha.application.shared.SecretStore
import io.github.roberbarras.racha.domain.ai.Summary
import io.github.roberbarras.racha.domain.library.ContentId
import kotlinx.coroutines.flow.Flow

data class ChatMessage(val role: String, val content: String)

/**
 * Outbound port: an OpenAI-compatible chat-completions API (H17). Errors are `RemoteError`s: `Rejected` with the
 * HTTP status (429 quota, 401/403 key, 402 payment, 404 model…) or `Unreachable`.
 */
interface ChatClient {
    suspend fun complete(baseUrl: String, apiKey: String?, model: String, messages: List<ChatMessage>, maxTokens: Int): String

    /** The models the provider offers (`GET /models`), to pick one. */
    suspend fun models(baseUrl: String, apiKey: String?): List<String>
}

/** Outbound port: generated summaries (synced). */
interface SummaryRepository {
    suspend fun get(target: ContentId): Summary?

    fun observe(target: ContentId): Flow<Summary?>

    suspend fun save(summary: Summary)

    suspend fun delete(target: ContentId)
}

/** The keyring entry of each provider's API key, by provider id (never in settings, exports or sync). */
fun interface ProviderKeys {
    fun of(providerId: String): SecretStore
}
