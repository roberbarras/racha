package io.github.roberbarras.racha.remote

import io.github.roberbarras.racha.application.ai.ChatClient
import io.github.roberbarras.racha.application.ai.ChatMessage
import io.github.roberbarras.racha.application.sync.RemoteError
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The OpenAI chat-completions protocol (H17), which every summary provider speaks: `POST {base}/chat/completions` and
 * `GET {base}/models`, the key as a bearer token. Errors keep the provider's status (429 no quota, 401 bad key…) so the
 * next provider can take over; the provider's own error code travels as [RemoteError.Rejected.code].
 */
class KtorChatClient(
    private val engineFactory: (HttpOptions) -> HttpClientEngine = ::okHttp,
    private val options: suspend () -> HttpOptions = { HttpOptions() },
) : ChatClient {
    override suspend fun complete(baseUrl: String, apiKey: String?, model: String, messages: List<ChatMessage>, maxTokens: Int): String {
        val body = buildJsonObject {
            put("model", model)
            putJsonArray("messages") { messages.forEach { m -> add(buildJsonObject { put("role", m.role); put("content", m.content) }) } }
            put("max_tokens", maxTokens)
            put("temperature", 0.3)
            put("stream", false)
        }
        val json = call(apiKey) { client ->
            client.post(endpoint(baseUrl, "chat/completions")) {
                apiKey?.let { bearerAuth(it) }
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
        }
        val message = runCatching { json["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject }.getOrNull()
            ?: throw RemoteError.Rejected(200, "unexpected_answer", null)
        return (message["content"] as? JsonPrimitive)?.contentOrNull.orEmpty().let(::withoutThinking)
    }

    override suspend fun models(baseUrl: String, apiKey: String?): List<String> {
        val json = call(apiKey) { client -> client.get(endpoint(baseUrl, "models")) { apiKey?.let { bearerAuth(it) } } }
        return runCatching { json["data"]!!.jsonArray.mapNotNull { (it.jsonObject["id"] as? JsonPrimitive)?.contentOrNull } }.getOrDefault(emptyList())
            .map { it.removePrefix("models/") } // Gemini lists "models/gemini-2.5-flash"
            .distinct()
    }

    private suspend fun call(apiKey: String?, request: suspend (HttpClient) -> HttpResponse): JsonObject {
        val config = options()
        HttpClient(engineFactory(config)) {
            expectSuccess = false
            install(HttpTimeout) {
                connectTimeoutMillis = 15_000
                requestTimeoutMillis = 240_000 // a long summary from a busy free tier takes a while
            }
            install(UserAgent) { agent = config.userAgent }
        }.use { client ->
            val response = try {
                request(client)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw RemoteError.Unreachable(e)
            }
            val text = response.bodyAsText()
            val json = runCatching { JSON.parseToJsonElement(text).jsonObject }.getOrNull()
            if (!response.status.isSuccess()) {
                val error = json?.get("error")
                val detail = (error as? JsonObject)?.let { (it["message"] as? JsonPrimitive)?.contentOrNull } ?: (error as? JsonPrimitive)?.contentOrNull
                val code = (error as? JsonObject)?.let { e -> listOf("code", "type", "status").firstNotNullOfOrNull { (e[it] as? JsonPrimitive)?.contentOrNull } }
                throw RemoteError.Rejected(response.status.value, code, detail?.take(300))
            }
            return json ?: throw RemoteError.Rejected(response.status.value, "unexpected_answer", null)
        }
    }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

        internal fun endpoint(baseUrl: String, path: String) = baseUrl.trim().trimEnd('/') + "/" + path

        /** Reasoning models (DeepSeek R1, Qwen…) may answer with their thinking first: only the answer is kept. */
        internal fun withoutThinking(text: String) = text.replace(Regex("(?s)<think>.*?</think>"), "").trim()
    }
}
