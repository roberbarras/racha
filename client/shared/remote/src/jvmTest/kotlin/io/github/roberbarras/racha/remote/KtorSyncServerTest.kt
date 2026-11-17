package io.github.roberbarras.racha.remote

import io.github.roberbarras.racha.application.sync.DeviceDescriptor
import io.github.roberbarras.racha.application.sync.PushOutcome
import io.github.roberbarras.racha.application.sync.RemoteError
import io.github.roberbarras.racha.application.sync.ServerAddress
import io.github.roberbarras.racha.application.sync.SyncChange
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant

class KtorSyncServerTest {
    private val address = ServerAddress("racha.example.com", 8443, https = true, basePath = "/api/")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun buildsTheBaseUrl() {
        assertEquals("https://racha.example.com:8443/api", address.baseUrl)
        assertEquals("https://racha.example.com", ServerAddress("racha.example.com", 443, true, "").baseUrl)
        assertEquals("http://192.168.1.10:8080", ServerAddress("192.168.1.10", 8080, false, "/").baseUrl)
    }

    @Test
    fun loginSendsTheDeviceAndReadsTokens() = runBlocking<Unit> {
        var body = ""
        val server = KtorSyncServer(MockEngine { request ->
            assertEquals("https://racha.example.com:8443/api/auth/login", request.url.toString())
            body = request.body.toByteArray().decodeToString()
            respond("""{"userId":"u","deviceId":"d","accessToken":"a","tokenType":"Bearer","expiresIn":900,"refreshToken":"r"}""", HttpStatusCode.OK, json)
        })
        val tokens = server.login(address, "ana@example.com", "correct horse battery", DeviceDescriptor(null, "Portátil", "linux", "0.0.1"))
        assertEquals("a", tokens.accessToken)
        assertEquals(900, tokens.expiresInSeconds)
        assertTrue(""""platform":"linux"""" in body && """"email":"ana@example.com"""" in body, body)
    }

    @Test
    fun errorsAreClassified() = runBlocking<Unit> {
        val rejected = KtorSyncServer(MockEngine {
            respond("""{"status":401,"code":"invalid_credentials","detail":"invalid email or password"}""", HttpStatusCode.Unauthorized, json)
        })
        val e = assertFailsWith<RemoteError.Rejected> { rejected.login(address, "a@b.c", "x", DeviceDescriptor(null, "x", "linux", "0")) }
        assertEquals("invalid_credentials", e.code)

        val expired = KtorSyncServer(MockEngine { respond("", HttpStatusCode.Unauthorized) })
        assertFailsWith<RemoteError.Unauthorized> { expired.pull(address, "token", 0, 10) }

        val offline = KtorSyncServer(MockEngine { throw java.net.ConnectException("connection refused") })
        assertFailsWith<RemoteError.Unreachable> { offline.pull(address, "token", 0, 10) }
    }

    @Test
    fun pushAndPullSpeakTheProtocol() = runBlocking<Unit> {
        val server = KtorSyncServer(MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/sync/push" -> {
                    assertEquals("Bearer t", request.headers[HttpHeaders.Authorization])
                    respond("""{"results":[{"entity":"progress","id":"e1","outcome":"stale","reason":"newer","server":
                        {"entity":"progress","id":"e1","updatedAt":"2026-11-16T10:00:00Z","deleted":false,"seq":7,"data":{"positionSec":90}}}],
                        "applied":0,"stale":1,"invalid":0}""", HttpStatusCode.OK, json)
                }
                else -> {
                    assertEquals("5", request.url.parameters["since"])
                    respond("""{"changes":[],"cursor":5,"hasMore":false}""", HttpStatusCode.OK, json)
                }
            }
        })
        val results = server.push(address, "t", listOf(SyncChange("progress", "e1", Instant.parse("2026-11-16T09:00:00Z"), false, JsonObject(mapOf("positionSec" to JsonPrimitive(30))))))
        assertEquals(PushOutcome.STALE, results.single().outcome)
        assertEquals(7, results.single().server!!.seq)
        assertEquals(5, server.pull(address, "t", 5, 100).cursor)
    }
    @Test
    fun passwordCallsSpeakTheProtocol() = runBlocking<Unit> {
        val seen = mutableListOf<String>()
        val server = KtorSyncServer(MockEngine { request ->
            val body = request.body.toByteArray().decodeToString()
            seen += request.url.encodedPath + " " + (request.headers[HttpHeaders.Authorization] ?: "-") + " " +
                (request.headers[HttpHeaders.AcceptLanguage] ?: "-") + " " + body
            when (request.url.encodedPath) {
                "/api/account/password" -> if ("wrong" in body) {
                    respond("""{"status":403,"code":"wrong_password","detail":"no"}""", HttpStatusCode.Forbidden, json)
                } else {
                    respond("", HttpStatusCode.NoContent)
                }
                "/api/auth/password/forgot" -> respond("", HttpStatusCode.Accepted)
                else -> respond("", HttpStatusCode.NoContent)
            }
        })
        server.changePassword(address, "t", "correct horse battery", "a brand new password")
        val wrong = assertFailsWith<RemoteError.Rejected> { server.changePassword(address, "t", "wrong", "a brand new password") }
        assertEquals("wrong_password", wrong.code)
        server.requestRecoveryCode(address, "ana@example.com", "es")
        server.resetPassword(address, "ana@example.com", "ABCD-2345", "a brand new password")

        assertEquals("/api/account/password Bearer t - " +
            """{"currentPassword":"correct horse battery","newPassword":"a brand new password"}""", seen[0])
        assertEquals("""/api/auth/password/forgot - es {"email":"ana@example.com"}""", seen[2])
        assertEquals("""/api/auth/password/reset - - {"email":"ana@example.com","code":"ABCD-2345","newPassword":"a brand new password"}""", seen[3])
    }
}
