package io.github.roberbarras.racha.remote.cast

import kotlinx.coroutines.runBlocking
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The files a TV fetches while casting (H18): ranges, subtitles as WebVTT, nothing that was not published. */
class LanMediaServerTest {
    private val dir = Files.createTempDirectory("racha-cast")
    private val video = dir.resolve("02 IAM básico.mp4").also { Files.write(it, ByteArray(10_000) { i -> (i % 251).toByte() }) }
    private val srt = dir.resolve("02 IAM básico.es.srt").also {
        Files.write(it, "1\r\n00:00:01,000 --> 00:00:02,500\r\nPolíticas\r\n".toByteArray(charset("windows-1252")))
    }
    private val server = LanMediaServer { InetAddress.getLoopbackAddress() }

    @AfterTest
    fun cleanUp() {
        server.stop()
        dir.toFile().deleteRecursively()
    }

    private fun open(url: String, method: String = "GET", range: String? = null) = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
        requestMethod = method
        connectTimeout = 5_000
        readTimeout = 10_000
        range?.let { setRequestProperty("Range", it) }
    }

    @Test
    fun aPublishedVideoIsServedWithRangesAndNothingElseIs() = runBlocking<Unit> {
        val url = server.publishFile(video.toUri().toString(), "video/mp4", "127.0.0.1")
        assertTrue(url.matches(Regex("http://127\\.0\\.0\\.1:\\d+/[0-9a-f]{32}/02%20IAM%20b%C3%A1sico\\.mp4")), url)

        val whole = open(url)
        assertEquals(200, whole.responseCode)
        assertEquals("10000", whole.getHeaderField("Content-Length"))
        assertEquals("bytes", whole.getHeaderField("Accept-Ranges"))
        assertEquals("Streaming", whole.getHeaderField("transferMode.dlna.org"))
        assertEquals(10_000, whole.inputStream.readBytes().size)

        val part = open(url, range = "bytes=9000-")
        assertEquals(206, part.responseCode)
        assertEquals("bytes 9000-9999/10000", part.getHeaderField("Content-Range"))
        assertEquals(Files.readAllBytes(video).copyOfRange(9000, 10_000).toList(), part.inputStream.readBytes().toList())
        assertEquals(500, open(url, range = "bytes=-500").inputStream.readBytes().size)
        assertEquals(416, open(url, range = "bytes=20000-").responseCode)
        val head = open(url, "HEAD")
        assertEquals(200, head.responseCode)
        assertEquals("video/mp4", head.contentType)

        // Another path on the same server, or a guessed token: nothing.
        val other = url.replaceAfterLast(":", "").let { url.substringBeforeLast('/').dropLast(32) + "0".repeat(32) + "/x.mp4" }
        assertEquals(404, open(other).responseCode)
        assertEquals(405, open(url, "DELETE").responseCode)
    }

    @Test
    fun subtitlesBecomeWebVttWithCorsAndStoppingClosesEverything() = runBlocking<Unit> {
        val url = server.publishSubtitle(srt.toUri().toString(), "127.0.0.1")
        assertTrue(url.endsWith(".es.vtt"))
        val vtt = open(url)
        assertEquals("text/vtt; charset=utf-8", vtt.contentType)
        assertEquals("*", vtt.getHeaderField("Access-Control-Allow-Origin"), "Chromecasts load text tracks with CORS")
        assertEquals("WEBVTT\n\n1\n00:00:01.000 --> 00:00:02.500\nPolíticas\n", vtt.inputStream.readBytes().decodeToString())

        server.stop()
        assertFailsWith<ConnectException> { open(url).responseCode }
    }

    @Test
    fun rangeHeaders() {
        assertEquals(0L to 99L, LanMediaServer.parseRange("bytes=0-99", 1000))
        assertEquals(900L to 999L, LanMediaServer.parseRange("bytes=-100", 1000))
        assertEquals(500L to 999L, LanMediaServer.parseRange("bytes=500-5000", 1000))
        assertEquals(null, LanMediaServer.parseRange("bytes=1000-", 1000))
        assertEquals(null, LanMediaServer.parseRange("items=0-1", 1000))
    }
}
