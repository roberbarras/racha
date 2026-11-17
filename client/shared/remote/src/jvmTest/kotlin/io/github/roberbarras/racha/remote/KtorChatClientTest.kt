package io.github.roberbarras.racha.remote

import io.github.roberbarras.racha.application.ai.ChatMessage
import io.github.roberbarras.racha.application.sync.RemoteError
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The OpenAI-compatible chat protocol (H17) against a simulated provider: never a real one in tests. */
class KtorChatClientTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun aChatCompletionIsPostedAndItsAnswerReturnedWithoutThinking() = runBlocking<Unit> {
        var url = ""
        var auth: String? = null
        var body = ""
        val client = KtorChatClient({ MockEngine { request ->
            url = request.url.toString()
            auth = request.headers[HttpHeaders.Authorization]
            body = String(request.body.toByteArray())
            respond("""{"id":"x","choices":[{"index":0,"message":{"role":"assistant","content":"<think>hmm</think>\n## Resumen\nIAM."}}]}""", HttpStatusCode.OK, json)
        } })
        val answer = client.complete("https://api.groq.com/openai/v1/", "gsk-test", "llama", listOf(ChatMessage("system", "s"), ChatMessage("user", "u")), 900)
        assertEquals("## Resumen\nIAM.", answer)
        assertEquals("https://api.groq.com/openai/v1/chat/completions", url)
        assertEquals("Bearer gsk-test", auth)
        val sent = Json.parseToJsonElement(body).jsonObject
        assertEquals("llama", sent["model"]!!.jsonPrimitive.content)
        assertEquals(900, sent["max_tokens"]!!.jsonPrimitive.int)
        assertEquals(listOf("system", "user"), sent["messages"]!!.jsonArray.map { it.jsonObject["role"]!!.jsonPrimitive.content })
    }

    @Test
    fun modelsAreListedWithoutGeminisPrefixAndNoKeyMeansNoAuthorization() = runBlocking<Unit> {
        var auth: String? = "unset"
        val client = KtorChatClient({ MockEngine { request ->
            auth = request.headers[HttpHeaders.Authorization]
            respond("""{"object":"list","data":[{"id":"models/gemini-2.5-flash"},{"id":"llama3.2"}]}""", HttpStatusCode.OK, json)
        } })
        assertEquals(listOf("gemini-2.5-flash", "llama3.2"), client.models("http://localhost:11434/v1", null))
        assertEquals(null, auth)
    }

    @Test
    fun providerErrorsKeepTheirStatusAndCode() = runBlocking<Unit> {
        val quota = KtorChatClient({ MockEngine {
            respond("""{"error":{"message":"Rate limit reached","type":"tokens","code":"rate_limit_exceeded"}}""", HttpStatusCode.TooManyRequests, json)
        } })
        val e = assertFailsWith<RemoteError.Rejected> { quota.complete("https://x/v1", "k", "m", listOf(ChatMessage("user", "u")), 10) }
        assertEquals(429, e.status)
        assertEquals("rate_limit_exceeded", e.code)
        assertEquals("Rate limit reached", e.message)

        // Gemini answers errors as a list.
        val gemini = KtorChatClient({ MockEngine { respond("""[{"error":{"code":400,"status":"INVALID_ARGUMENT"}}]""", HttpStatusCode.BadRequest, json) } })
        assertEquals(400, assertFailsWith<RemoteError.Rejected> { gemini.models("https://x", "k") }.status)
        val weird = KtorChatClient({ MockEngine { respond("""{"choices":[]}""", HttpStatusCode.OK, json) } })
        assertEquals("unexpected_answer", assertFailsWith<RemoteError.Rejected> { weird.complete("https://x", null, "m", emptyList(), 1) }.code)
        val offline = KtorChatClient({ MockEngine { throw java.net.ConnectException("refused") } })
        assertFailsWith<RemoteError.Unreachable> { offline.complete("http://localhost:11434/v1", null, "m", emptyList(), 1) }
    }
}
