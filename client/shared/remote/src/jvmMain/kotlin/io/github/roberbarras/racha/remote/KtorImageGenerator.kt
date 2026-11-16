package io.github.roberbarras.racha.remote

import io.github.roberbarras.racha.application.library.GeneratedImage
import io.github.roberbarras.racha.application.library.ImageGenerator
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
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Image generation over HTTP, two common shapes: an endpoint with `{prompt}` is fetched with GET (Pollinations:
 * `https://image.pollinations.ai/prompt/{prompt}`); otherwise the prompt is POSTed as `{"inputs": …}` (Hugging Face
 * Inference). The key, if any, goes as a bearer token. Proxy and User-Agent as configured.
 */
class KtorImageGenerator(
    private val engineFactory: (HttpOptions) -> HttpClientEngine = ::okHttp,
    private val options: suspend () -> HttpOptions = { HttpOptions() },
) : ImageGenerator {
    override suspend fun generate(endpoint: String, apiKey: String?, prompt: String): GeneratedImage {
        val config = options()
        HttpClient(engineFactory(config)) {
            expectSuccess = false
            install(HttpTimeout) {
                connectTimeoutMillis = 15_000
                requestTimeoutMillis = 180_000 // generating an image takes a while
            }
            install(UserAgent) { agent = config.userAgent }
        }.use { client ->
            val response = try {
                if ("{prompt}" in endpoint) {
                    client.get(endpoint.replace("{prompt}", prompt.encodeURLPathPart())) { apiKey?.let { bearerAuth(it) } }
                } else {
                    client.post(endpoint) {
                        apiKey?.let { bearerAuth(it) }
                        contentType(ContentType.Application.Json)
                        setBody(buildJsonObject { put("inputs", prompt) }.toString())
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw RemoteError.Unreachable(e)
            }
            return image(response)
        }
    }

    private suspend fun image(response: HttpResponse): GeneratedImage {
        if (response.status.value == 401 || response.status.value == 403) throw RemoteError.Rejected(response.status.value, "unauthorized", null)
        if (!response.status.isSuccess()) throw RemoteError.Rejected(response.status.value, null, null)
        val bytes = response.readRawBytes()
        if (bytes.size > MAX_BYTES) throw RemoteError.Rejected(response.status.value, "too_large", null)
        val extension = extensionOf(bytes) ?: throw RemoteError.Rejected(response.status.value, "not_an_image", null)
        return GeneratedImage(bytes, extension)
    }

    companion object {
        private const val MAX_BYTES = 15 shl 20

        /** By the file's own signature, not by what the server claims. */
        internal fun extensionOf(bytes: ByteArray): String? = when {
            bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte() -> "png"
            bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> "jpg"
            bytes.size >= 12 && String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WEBP" -> "webp"
            else -> null
        }
    }
}
