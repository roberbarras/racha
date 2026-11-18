package io.github.roberbarras.racha.remote.storage

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.roberbarras.racha.application.remote.RemoteCredentials
import io.github.roberbarras.racha.application.sync.RemoteError
import io.github.roberbarras.racha.domain.remote.RemoteCourses
import kotlinx.coroutines.runBlocking
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.file.Files
import java.util.Base64
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Remote folders (H19) against a simulated Nextcloud and a simulated Google: never the real services in tests. */
class RemoteStorageTest {
    private val video = ByteArray(300_000) { (it % 253).toByte() }
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    private val base get() = "http://127.0.0.1:${server.address.port}"
    private val ranges: MutableList<String?> = Collections.synchronizedList(mutableListOf())
    private val dir = Files.createTempDirectory("racha-remote")

    @AfterTest
    fun cleanUp() {
        server.stop(0)
        dir.toFile().deleteRecursively()
    }

    private fun HttpExchange.send(code: Int, body: ByteArray, type: String = "application/json", headers: Map<String, String> = emptyMap()) {
        responseHeaders.add("Content-Type", type)
        headers.forEach { (k, v) -> responseHeaders.add(k, v) }
        sendResponseHeaders(code, if (body.isEmpty()) -1 else body.size.toLong())
        responseBody.use { if (body.isNotEmpty()) it.write(body) }
    }

    private fun HttpExchange.sendRange(bytes: ByteArray) {
        val range = requestHeaders.getFirst("Range")
        ranges += range
        val from = range?.removePrefix("bytes=")?.substringBefore('-')?.toInt() ?: 0
        val part = bytes.copyOfRange(from, bytes.size)
        send(if (range != null) 206 else 200, part, "video/mp4", if (range != null) mapOf("Content-Range" to "bytes $from-${bytes.size - 1}/${bytes.size}") else emptyMap())
    }

    // ---- Nextcloud ----------------------------------------------------------------------------------------------

    private fun response(href: String, dir: Boolean, size: Long = 0) = """<d:response><d:href>$href</d:href><d:propstat><d:prop>""" +
        (if (dir) "<d:resourcetype><d:collection/></d:resourcetype>" else "<d:resourcetype/><d:getcontentlength>$size</d:getcontentlength>") +
        """<d:getlastmodified>Sun, 01 Nov 2026 10:00:00 GMT</d:getlastmodified></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"""

    private fun nextcloud() {
        val dav = "/remote.php/dav/files/ana%20p"
        server.createContext("/") { ex ->
            val auth = ex.requestHeaders.getFirst("Authorization")
            if (ex.requestURI.path.startsWith("/remote.php") && auth != "Basic " + Base64.getEncoder().encodeToString("ana p:app-pass".toByteArray())) {
                return@createContext ex.send(401, ByteArray(0))
            }
            val path = ex.requestURI.rawPath
            when {
                ex.requestMethod == "PROPFIND" -> {
                    assertEquals("1", ex.requestHeaders.getFirst("Depth"))
                    val body = when (path.trimEnd('/')) {
                        dav -> listOf(response("$dav/", true), response("$dav/Cursos/", true), response("$dav/notas.txt", false, 5))
                        "$dav/Cursos" -> listOf(response("$dav/Cursos/", true), response("$dav/Cursos/AWS%20b%C3%A1sico/", true))
                        "$dav/Cursos/AWS%20b%C3%A1sico" -> listOf(
                            response("$dav/Cursos/AWS%20b%C3%A1sico/", true), response("$dav/Cursos/AWS%20b%C3%A1sico/01%20-%20Intro/", true),
                        )
                        "$dav/Cursos/AWS%20b%C3%A1sico/01%20-%20Intro" -> listOf(
                            response("$dav/Cursos/AWS%20b%C3%A1sico/01%20-%20Intro/", true),
                            response("$dav/Cursos/AWS%20b%C3%A1sico/01%20-%20Intro/01%20Bienvenida.mp4", false, video.size.toLong()),
                        )
                        else -> return@createContext ex.send(404, ByteArray(0))
                    }
                    ex.send(207, ("""<?xml version="1.0"?><d:multistatus xmlns:d="DAV:">""" + body.joinToString("") + "</d:multistatus>").toByteArray(), "application/xml")
                }
                ex.requestMethod == "GET" && path.endsWith("Bienvenida.mp4") -> ex.sendRange(video)
                path == "/index.php/login/v2" -> ex.send(200, """{"poll":{"token":"tok","endpoint":"$base/login/v2/poll"},"login":"$base/login/v2/flow/abc"}""".toByteArray())
                path == "/login/v2/poll" -> {
                    val polls = ranges.count { it == "poll" }
                    ranges += "poll"
                    assertTrue(ex.requestBody.readBytes().decodeToString().contains("token=tok"))
                    if (polls == 0) ex.send(404, ByteArray(0))
                    else ex.send(200, """{"server":"$base","loginName":"ana p","appPassword":"app-pass"}""".toByteArray())
                }
                else -> ex.send(404, ByteArray(0))
            }
        }
        server.start()
    }

    @Test
    fun nextcloudSignsInBrowsesListsAndResumesADownload() = runBlocking<Unit> {
        nextcloud()
        val login = NextcloudLogin()
        val flow = login.start(base)
        assertEquals("$base/login/v2/flow/abc", flow.loginUrl)
        assertEquals(null, login.poll(flow), "not signed in yet")
        val account = login.poll(flow)!!
        assertEquals("$base/remote.php/dav/files/ana%20p/", account.davBase)
        val credentials = RemoteCredentials.WebDav(account.user, account.appPassword)

        val storage = WebDavStorage()
        val top = storage.folders(credentials, account.davBase, null)
        assertEquals(listOf("Cursos"), top.map { it.name })
        assertEquals("/Cursos", top.single().path)
        val inside = storage.folders(credentials, account.davBase, top.single().address)
        assertEquals(listOf("AWS básico"), inside.map { it.name })

        val files = storage.files(credentials, top.single().address)
        assertEquals(listOf("AWS básico/01 - Intro/01 Bienvenida.mp4"), files.map { it.path.value })
        assertEquals(video.size.toLong(), files.single().size)

        // An earlier attempt left 100 000 bytes: only the rest is asked for.
        val target = dir.resolve("AWS básico/01 - Intro/01 Bienvenida.mp4")
        Files.createDirectories(target.parent)
        Files.write(target.resolveSibling(target.fileName.toString() + RemoteCourses.PART), video.copyOf(100_000))
        var last = 0L
        storage.download(credentials, files.single(), target.toUri().toString()) { last = it }
        assertEquals("bytes=100000-", ranges.last())
        assertEquals(video.toList(), Files.readAllBytes(target).toList())
        assertEquals(video.size.toLong(), last)
        assertEquals(files.single().modifiedAt.toEpochMilliseconds(), Files.getLastModifiedTime(target).toMillis(), "the remote time, to compare next time")

        assertFailsWith<RemoteError.Unauthorized> { storage.files(RemoteCredentials.WebDav("ana p", "wrong"), top.single().address) }
    }

    // ---- Google Drive -------------------------------------------------------------------------------------------

    private fun google() {
        server.createContext("/") { ex ->
            val path = ex.requestURI.path
            val query = (ex.requestURI.rawQuery ?: "").split('&').filter { '=' in it }
                .associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8) }
            when {
                path == "/token" -> {
                    val form = ex.requestBody.readBytes().decodeToString().split('&')
                        .associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8) }
                    assertEquals("client-1", form["client_id"])
                    when (form["grant_type"]) {
                        "authorization_code" -> {
                            assertEquals("the-code", form["code"])
                            assertTrue(!form["code_verifier"].isNullOrEmpty())
                            ex.send(200, """{"access_token":"at-0","refresh_token":"rt-1","expires_in":3599}""".toByteArray())
                        }
                        else -> if (form["refresh_token"] == "rt-1") ex.send(200, """{"access_token":"at-1","expires_in":3599}""".toByteArray())
                            else ex.send(400, """{"error":"invalid_grant"}""".toByteArray())
                    }
                }
                path == "/drive/v3/files" -> {
                    assertEquals("Bearer at-1", ex.requestHeaders.getFirst("Authorization"))
                    val q = query["q"] ?: ""
                    val json = when {
                        q.startsWith("'root' in parents") -> """{"files":[{"id":"f-cursos","name":"Cursos","mimeType":"application/vnd.google-apps.folder"}]}"""
                        q.startsWith("'f-cursos' in parents") && query["pageToken"] == null ->
                            """{"nextPageToken":"p2","files":[{"id":"f-go","name":"Go","mimeType":"application/vnd.google-apps.folder","modifiedTime":"2026-11-01T10:00:00Z"}]}"""
                        q.startsWith("'f-cursos' in parents") ->
                            """{"files":[{"id":"doc","name":"Apuntes","mimeType":"application/vnd.google-apps.document"}]}"""
                        q.startsWith("'f-go' in parents") ->
                            """{"files":[{"id":"v1","name":"01 Intro.mp4","mimeType":"video/mp4","size":"${video.size}","modifiedTime":"2026-11-01T10:00:00Z"}]}"""
                        else -> """{"files":[]}"""
                    }
                    ex.send(200, json.toByteArray())
                }
                path == "/drive/v3/files/v1" -> { assertEquals("media", query["alt"]); ex.sendRange(video) }
                else -> ex.send(404, ByteArray(0))
            }
        }
        server.start()
    }

    @Test
    fun googleSignsInWithPkceListsDriveAndDownloads() = runBlocking<Unit> {
        google()
        val app = GoogleClient("client-1", "secret-1")
        assertEquals(app, GoogleClient.decode(app.encode()))
        val auth = GoogleDriveAuth(authorizeUrl = "$base/auth", tokenUrl = "$base/token")
        // The "browser": checks what is asked and comes back to the loopback address with the code.
        val refresh = auth.signIn(app) { url ->
            val params = URI(url).rawQuery.split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8) }
            assertEquals(GoogleDriveAuth.SCOPE, params["scope"])
            assertEquals("S256", params["code_challenge_method"])
            assertEquals("offline", params["access_type"])
            Thread {
                (URI(params["redirect_uri"] + "/?state=${params["state"]}&code=the-code").toURL().openConnection() as HttpURLConnection).inputStream.readBytes()
            }.start()
        }
        assertEquals("rt-1", refresh)

        val drive = GoogleDriveStorage({ app }, api = "$base/drive/v3", tokenUrl = "$base/token")
        val credentials = RemoteCredentials.Drive(refresh)
        assertEquals(listOf("Compartido conmigo", "Mi unidad"), drive.folders(credentials, "", null).map { it.name })
        assertEquals(listOf("Cursos"), drive.folders(credentials, "", "root").map { it.name })
        val files = drive.files(credentials, "f-cursos")
        assertEquals(listOf("Go/01 Intro.mp4"), files.map { it.path.value }, "pages followed, Google Docs left out")

        val target = dir.resolve("Go/01 Intro.mp4")
        drive.download(credentials, files.single(), target.toUri().toString()) {}
        assertEquals(video.toList(), Files.readAllBytes(target).toList())

        assertFailsWith<RemoteError.Unauthorized> { drive.files(RemoteCredentials.Drive("revoked"), "f-cursos") }
    }
}
