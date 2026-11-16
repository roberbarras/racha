package io.github.roberbarras.racha.remote

import io.github.roberbarras.racha.application.sync.RemoteError
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Image generation (spec §3, optional) against a simulated service: never a real one in tests. */
class KtorImageGeneratorTest {
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10, 0, 0)

    @Test
    fun anEndpointWithPromptIsFetchedAndOtherwiseThePromptIsPosted() = runBlocking<Unit> {
        var seen = ""
        val get = KtorImageGenerator({ MockEngine { request ->
            seen = "${request.method.value} ${request.url}"
            respond(png, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/png"))
        } })
        assertEquals("png", get.generate("https://image.example.com/prompt/{prompt}", null, "Café & Go: 101").extension)
        assertEquals("GET https://image.example.com/prompt/Caf%C3%A9%20&%20Go:%20101", seen)

        var body = ""
        var auth: String? = null
        val post = KtorImageGenerator({ MockEngine { request ->
            body = String(request.body.toByteArray())
            auth = request.headers[HttpHeaders.Authorization]
            assertEquals(HttpMethod.Post, request.method)
            respond(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0), HttpStatusCode.OK)
        } })
        assertEquals("jpg", post.generate("https://api.example.com/models/x", "secret-key", "a poster").extension)
        assertEquals("""{"inputs":"a poster"}""", body)
        assertEquals("Bearer secret-key", auth)
    }

    @Test
    fun anythingButAnImageIsAnError() = runBlocking<Unit> {
        val html = KtorImageGenerator({ MockEngine { respond("<html>busy</html>", HttpStatusCode.OK) } })
        assertEquals("not_an_image", assertFailsWith<RemoteError.Rejected> { html.generate("https://x/{prompt}", null, "p") }.code)
        val denied = KtorImageGenerator({ MockEngine { respond("", HttpStatusCode.Unauthorized) } })
        assertEquals(401, assertFailsWith<RemoteError.Rejected> { denied.generate("https://x", "bad", "p") }.status)
        val offline = KtorImageGenerator({ MockEngine { throw java.net.ConnectException("refused") } })
        assertFailsWith<RemoteError.Unreachable> { offline.generate("https://x", null, "p") }
    }
}
