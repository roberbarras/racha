package io.github.roberbarras.racha.spike.webview

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.HexFormat
import java.util.concurrent.Executors

/**
 * Serves one course folder on 127.0.0.1 under an unguessable path prefix (ADR-0003). Anything whose real path
 * escapes the folder, or a request without the token, gets 404.
 */
class CourseFileServer(courseRoot: Path) : AutoCloseable {
    private val root: Path = courseRoot.toRealPath()
    private val token: String = HexFormat.of().formatHex(ByteArray(16).also(SecureRandom()::nextBytes))
    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}/$token/"

    init {
        server.executor = Executors.newVirtualThreadPerTaskExecutor()
        server.createContext("/") { exchange -> exchange.use(::handle) }
        server.start()
    }

    fun urlFor(relativePath: String): String =
        URI("http", null, "127.0.0.1", server.address.port, "/$token/$relativePath", null, null).toASCIIString()

    private fun handle(exchange: HttpExchange) {
        val resolved = resolve(exchange.requestURI.rawPath)
        if (exchange.requestMethod !in setOf("GET", "HEAD") || resolved == null) {
            exchange.sendResponseHeaders(404, -1)
            return
        }
        exchange.responseHeaders.apply {
            add("Content-Type", contentType(resolved))
            add("Cache-Control", "no-store")
            add("X-Content-Type-Options", "nosniff")
            add("Referrer-Policy", "no-referrer")
        }
        val size = Files.size(resolved)
        if (exchange.requestMethod == "HEAD") {
            exchange.sendResponseHeaders(200, -1)
            return
        }
        exchange.sendResponseHeaders(200, if (size == 0L) -1 else size)
        Files.newInputStream(resolved).use { it.transferTo(exchange.responseBody) }
    }

    /** Decodes the raw path (so `%2e%2e` cannot sneak past) and accepts only real files inside the course root. */
    private fun resolve(rawPath: String): Path? {
        val prefix = "/$token/"
        if (!rawPath.startsWith(prefix)) return null
        val decoded = runCatching { URI.create(rawPath).path }.getOrNull()?.removePrefix(prefix) ?: return null
        val candidate = runCatching { root.resolve(decoded).normalize().toRealPath() }.getOrNull() ?: return null
        return candidate.takeIf { it.startsWith(root) && Files.isRegularFile(it) }
    }

    private fun contentType(file: Path): String = when (file.fileName.toString().substringAfterLast('.').lowercase()) {
        "html", "htm" -> "text/html; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "js", "mjs" -> "text/javascript; charset=utf-8"
        "json" -> "application/json"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "woff2" -> "font/woff2"
        "txt", "url" -> "text/plain; charset=utf-8"
        else -> "application/octet-stream"
    }

    override fun close() = server.stop(0)
}
