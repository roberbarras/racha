package io.github.roberbarras.racha.remote.storage

import io.github.roberbarras.racha.application.remote.RemoteCredentials
import io.github.roberbarras.racha.application.remote.RemoteDirectory
import io.github.roberbarras.racha.application.remote.RemoteStorage
import io.github.roberbarras.racha.application.remote.GoogleSignIn
import io.github.roberbarras.racha.application.sync.RemoteError
import io.github.roberbarras.racha.domain.library.RelativePath
import io.github.roberbarras.racha.domain.remote.RemoteFile
import io.github.roberbarras.racha.domain.remote.RemoteProvider
import io.github.roberbarras.racha.remote.HttpOptions
import io.github.roberbarras.racha.remote.okHttp
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsText
import io.ktor.http.parameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Instant

/**
 * The OAuth client the user created in Google Cloud (type "Desktop app"; both apps use it). Its secret is not
 * confidential for installed apps (Google says so), but it is kept in the keyring with everything else.
 */
data class GoogleClient(val id: String, val secret: String) {
    fun encode() = "$id\n$secret"

    companion object {
        fun decode(text: String?): GoogleClient? = text?.lines()?.takeIf { it.size >= 2 && it[0].isNotBlank() }?.let { GoogleClient(it[0].trim(), it[1].trim()) }
    }
}

/**
 * Signing in to Google Drive (H19) as Google wants installed apps to: the browser, PKCE, and the answer on a one-off
 * loopback address of this device. Only read access to Drive is asked for; the refresh token goes to the keyring.
 */
class GoogleDriveAuth(
    private val client: suspend () -> GoogleClient? = { null },
    engineFactory: (HttpOptions) -> HttpClientEngine = ::okHttp,
    options: suspend () -> HttpOptions = { HttpOptions() },
    private val authorizeUrl: String = "https://accounts.google.com/o/oauth2/v2/auth",
    private val tokenUrl: String = TOKEN_URL,
) : GoogleSignIn {
    private val http = RemoteHttp(engineFactory, options)

    override suspend fun signIn(openBrowser: (String) -> Unit): String =
        signIn(client() ?: throw RemoteError.Rejected(400, "no_google_client", null), openBrowser)

    /** Opens the consent page with [openBrowser] and waits (up to 5 min) for the answer; returns the refresh token. */
    suspend fun signIn(client: GoogleClient, openBrowser: (String) -> Unit): String {
        val random = SecureRandom()
        fun token(bytes: Int) = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also(random::nextBytes))
        val verifier = token(48)
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val state = token(16)
        val code = ServerSocket().use { server ->
            server.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
            val redirect = "http://127.0.0.1:${server.localPort}"
            val url = authorizeUrl + "?" + listOf(
                "client_id" to client.id, "redirect_uri" to redirect, "response_type" to "code", "scope" to SCOPE,
                "code_challenge" to challenge, "code_challenge_method" to "S256", "state" to state,
                "access_type" to "offline", "prompt" to "consent",
            ).joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, Charsets.UTF_8) }
            openBrowser(url)
            withTimeout(5 * 60_000) { runInterruptible(Dispatchers.IO) { awaitCode(server, state) } } to redirect
        }
        return http.use { http ->
            val response = http.submitForm(tokenUrl, parameters {
                append("client_id", client.id); append("client_secret", client.secret); append("code", code.first)
                append("code_verifier", verifier); append("grant_type", "authorization_code"); append("redirect_uri", code.second)
            })
            RemoteHttp.check(response, "token")
            Json.parseToJsonElement(response.bodyAsText()).jsonObject.str("refresh_token") ?: throw RemoteError.Rejected(200, "no_refresh_token", null)
        }
    }

    /** The browser comes back to the loopback address with `?code=…&state=…`; anything else is answered and ignored. */
    private fun awaitCode(server: ServerSocket, state: String): String {
        server.soTimeout = 1_000
        while (true) {
            val socket = try { server.accept() } catch (_: java.net.SocketTimeoutException) { if (Thread.interrupted()) throw InterruptedException(); continue }
            socket.use {
                it.soTimeout = 10_000
                val line = it.getInputStream().bufferedReader().readLine() ?: return@use
                val query = line.split(' ').getOrNull(1)?.substringAfter('?', "") ?: ""
                val params = query.split('&').filter { p -> '=' in p }.associate { p ->
                    p.substringBefore('=') to URLDecoder.decode(p.substringAfter('='), Charsets.UTF_8)
                }
                val ok = params["state"] == state && params["code"] != null
                val body = if (ok) PAGE_OK else PAGE_ERROR
                it.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.toByteArray().size}\r\n" +
                    "Connection: close\r\n\r\n$body").toByteArray())
                it.getOutputStream().flush()
                if (ok) return params.getValue("code")
                if (params["state"] == state && params["error"] != null) throw RemoteError.Rejected(403, params["error"], "consent: ${params["error"]}")
            }
        }
    }

    companion object {
        const val SCOPE = "https://www.googleapis.com/auth/drive.readonly"
        const val TOKEN_URL = "https://oauth2.googleapis.com/token"
        private const val PAGE_OK = "<!doctype html><meta charset=utf-8><title>Racha</title><body style=\"font-family:sans-serif;padding:2em\">" +
            "<h1>Listo · Done</h1><p>Ya puedes volver a Racha. · You can go back to Racha now.</p>"
        private const val PAGE_ERROR = "<!doctype html><meta charset=utf-8><title>Racha</title><body style=\"font-family:sans-serif;padding:2em\">" +
            "<h1>Racha</h1><p>No se ha completado el acceso. · Sign-in did not complete.</p>"
    }
}

/** Google Drive (H19): folders by id, files listed recursively, downloads with `Range`. */
class GoogleDriveStorage(
    private val client: suspend () -> GoogleClient?,
    engineFactory: (HttpOptions) -> HttpClientEngine = ::okHttp,
    options: suspend () -> HttpOptions = { HttpOptions() },
    private val api: String = "https://www.googleapis.com/drive/v3",
    private val tokenUrl: String = GoogleDriveAuth.TOKEN_URL,
) : RemoteStorage {
    private val http = RemoteHttp(engineFactory, options)
    override val provider = RemoteProvider.GOOGLE_DRIVE

    private data class Access(val token: String, val until: Long)
    private val access = ConcurrentHashMap<String, Access>()

    private data class Item(val id: String, val name: String, val folder: Boolean, val size: Long?, val modifiedAt: Instant)

    override suspend fun folders(credentials: RemoteCredentials, base: String, address: String?): List<RemoteDirectory> = http.use { client ->
        val token = token(client, credentials)
        when (address) {
            null -> listOf(RemoteDirectory(MY_DRIVE, "root", "/"), RemoteDirectory(SHARED, SHARED_ADDRESS, "/$SHARED"))
            SHARED_ADDRESS -> list(client, token, "sharedWithMe and mimeType='$FOLDER' and trashed=false")
                .map { RemoteDirectory(it.name, it.id, "/$SHARED/${it.name}") }
            else -> list(client, token, "'$address' in parents and mimeType='$FOLDER' and trashed=false")
                .map { RemoteDirectory(it.name, it.id, it.name) }
        }.sortedBy { it.name.lowercase() }
    }

    override suspend fun files(credentials: RemoteCredentials, address: String): List<RemoteFile> = http.use { client ->
        val token = token(client, credentials)
        val out = ArrayList<RemoteFile>()
        val pending = ArrayDeque(listOf(address to ""))
        while (pending.isNotEmpty()) {
            val (id, prefix) = pending.removeFirst()
            for (item in list(client, token, "'$id' in parents and trashed=false")) {
                val path = if (prefix.isEmpty()) item.name else "$prefix/${item.name}"
                when {
                    item.folder -> pending += item.id to path
                    // Google Docs have no size and cannot be downloaded as files: not course material here.
                    item.size == null -> Unit
                    else -> runCatching { RelativePath.of(path) }.getOrNull()?.let { out += RemoteFile(it, item.size, item.modifiedAt, item.id) }
                }
            }
        }
        out
    }

    override suspend fun download(credentials: RemoteCredentials, file: RemoteFile, target: String, progress: (Long) -> Unit) {
        http.use { client ->
            val token = token(client, credentials)
            client.downloadTo(file, target, progress) {
                url("$api/files/${file.ref}")
                parameter("alt", "media")
                parameter("supportsAllDrives", "true")
                bearerAuth(token)
            }
        }
    }

    private suspend fun list(client: HttpClient, token: String, query: String): List<Item> {
        val out = ArrayList<Item>()
        var page: String? = null
        do {
            val response = client.get("$api/files") {
                bearerAuth(token)
                parameter("q", query)
                parameter("fields", "nextPageToken,files(id,name,mimeType,size,modifiedTime)")
                parameter("pageSize", "1000")
                parameter("supportsAllDrives", "true")
                parameter("includeItemsFromAllDrives", "true")
                page?.let { parameter("pageToken", it) }
            }
            RemoteHttp.check(response, "list")
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            (json["files"] as? JsonArray).orEmpty().forEach { element ->
                val f = element.jsonObject
                out += Item(
                    f.str("id") ?: return@forEach, f.str("name") ?: "", f.str("mimeType") == FOLDER,
                    f.str("size")?.toLongOrNull() ?: (f["size"] as? JsonPrimitive)?.longOrNull,
                    f.str("modifiedTime")?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: Instant.fromEpochMilliseconds(0),
                )
            }
            page = json.str("nextPageToken")
        } while (page != null)
        return out
    }

    /** An access token from the refresh token, kept until a minute before it expires. */
    private suspend fun token(http: HttpClient, credentials: RemoteCredentials): String {
        val refresh = (credentials as? RemoteCredentials.Drive)?.refreshToken ?: throw RemoteError.Unauthorized()
        access[refresh]?.takeIf { it.until > System.currentTimeMillis() }?.let { return it.token }
        val app = client() ?: throw RemoteError.Rejected(400, "no_google_client", null)
        val response = http.submitForm(tokenUrl, parameters {
            append("client_id", app.id); append("client_secret", app.secret)
            append("refresh_token", refresh); append("grant_type", "refresh_token")
        })
        // A revoked or expired consent comes back as 400 invalid_grant: sign in again.
        if (response.status.value == 400 || response.status.value == 401) throw RemoteError.Unauthorized()
        RemoteHttp.check(response, "token")
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val token = json.str("access_token") ?: throw RemoteError.Unauthorized()
        val seconds = (json["expires_in"] as? JsonPrimitive)?.longOrNull ?: 3_600
        access[refresh] = Access(token, System.currentTimeMillis() + (seconds - 60) * 1000)
        return token
    }

    companion object {
        const val FOLDER = "application/vnd.google-apps.folder"
        const val SHARED_ADDRESS = "shared"
        const val MY_DRIVE = "Mi unidad"
        const val SHARED = "Compartido conmigo"
    }
}

private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
