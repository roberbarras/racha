package io.github.roberbarras.racha.desktop.resource

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** ADR-0003: a course page can load its own files and nothing else (same checks as the H1 spike, now in the build). */
class CourseFileServerTest {
    private val base: Path = Files.createTempDirectory("racha-courses")
    private val course: Path = Files.createDirectories(base.resolve("AWS Course/01 - Introduction"))
    private val other: Path = Files.createDirectories(base.resolve("Other Course"))
    private val server: CourseFileServer
    private val http = HttpClient.newHttpClient()

    init {
        Files.writeString(course.resolve("003 Troubleshooting.html"), "<link rel=stylesheet href='../css/site.css'><h1>Hi</h1>")
        Files.createDirectories(base.resolve("AWS Course/css")).resolve("site.css").let { Files.writeString(it, "h1{color:red}") }
        Files.writeString(other.resolve("secret.txt"), "other course")
        Files.createSymbolicLink(base.resolve("AWS Course/escape"), other)
        server = CourseFileServer(base.resolve("AWS Course"))
    }

    @AfterTest
    fun close() {
        server.close()
        base.toFile().deleteRecursively()
    }

    private fun get(rawUrl: String): HttpResponse<String> =
        http.send(HttpRequest.newBuilder(URI.create(rawUrl)).GET().build(), HttpResponse.BodyHandlers.ofString())

    @Test
    fun servesThePageAndItsRelativeFiles() {
        val page = get(server.urlFor("01 - Introduction/003 Troubleshooting.html"))
        assertEquals(200, page.statusCode())
        assertTrue(page.headers().firstValue("Content-Type").get().startsWith("text/html"))
        val css = get(URI.create(server.urlFor("01 - Introduction/003 Troubleshooting.html")).resolve("../css/site.css").toString())
        assertEquals("h1{color:red}", css.body())
    }

    @Test
    fun nothingOutsideTheCourse() {
        val root = server.baseUrl
        val port = URI.create(root).port
        listOf(
            root + "../Other%20Course/secret.txt",
            root + "%2e%2e/Other%20Course/secret.txt",
            root + "%2E%2E%2FOther%20Course%2Fsecret.txt",
            root + "/etc/passwd",
            root + "escape/secret.txt", // symlink pointing outside
            "http://127.0.0.1:$port/AWS%20Course/01%20-%20Introduction/003%20Troubleshooting.html", // no token
            "http://127.0.0.1:$port/0123456789abcdef0123456789abcdef/01%20-%20Introduction/003%20Troubleshooting.html", // wrong token
            root + "01%20-%20Introduction", // a folder
        ).forEach { url -> assertEquals(404, get(url).statusCode(), url) }
    }

    @Test
    fun onlyReads() {
        val post = http.send(
            HttpRequest.newBuilder(URI.create(server.urlFor("01 - Introduction/003 Troubleshooting.html"))).POST(HttpRequest.BodyPublishers.ofString("x")).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(404, post.statusCode())
        assertTrue(server.serves(server.urlFor("01 - Introduction/003 Troubleshooting.html")))
        assertTrue(!server.serves("https://example.com/"))
    }
}
