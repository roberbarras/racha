package io.github.roberbarras.racha.remote.cast

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI

/**
 * HTTP/1.1 to devices on the local network (UPnP descriptions and SOAP control). TVs speak plain `http`, which Android
 * forbids to its HTTP clients by default; a socket is not affected, and this is only ever used for LAN devices found
 * by discovery, never for the internet.
 */
internal object LanHttp {
    data class Response(val status: Int, val headers: Map<String, String>, val body: String)

    fun request(url: String, method: String = "GET", headers: Map<String, String> = emptyMap(), body: String? = null, timeoutMs: Int = 8_000): Response {
        val uri = URI(url)
        require(uri.scheme == "http") { "only http: $url" }
        val port = if (uri.port > 0) uri.port else 80
        val path = (uri.rawPath?.ifEmpty { "/" } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
        Socket().use { socket ->
            socket.connect(InetSocketAddress(uri.host, port), timeoutMs)
            socket.soTimeout = timeoutMs
            val bytes = body?.toByteArray(Charsets.UTF_8)
            val request = buildString {
                append("$method $path HTTP/1.1\r\n")
                append("Host: ${uri.host}:$port\r\n")
                append("User-Agent: Racha/1 UPnP/1.0 DLNADOC/1.50\r\n")
                headers.forEach { (k, v) -> append(k).append(": ").append(v).append("\r\n") }
                if (bytes != null) append("Content-Length: ${bytes.size}\r\n")
                append("Connection: close\r\n\r\n")
            }
            val out = socket.getOutputStream()
            out.write(request.toByteArray(Charsets.ISO_8859_1))
            bytes?.let(out::write)
            out.flush()
            return read(BufferedInputStream(socket.getInputStream()))
        }
    }

    private fun read(input: InputStream): Response {
        val statusLine = line(input) ?: throw IOException("no response")
        val status = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("bad status line: $statusLine")
        val headers = HashMap<String, String>()
        while (true) {
            val line = line(input) ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        val body = ByteArrayOutputStream()
        val length = headers["content-length"]?.toIntOrNull()
        when {
            headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true -> while (true) {
                val size = line(input)?.substringBefore(';')?.trim()?.toIntOrNull(16) ?: break
                if (size == 0) break
                body.write(exactly(input, size))
                line(input)
            }
            length != null -> body.write(exactly(input, length))
            else -> input.copyTo(body)
        }
        if (body.size() > MAX_BODY) throw IOException("response too large")
        return Response(status, headers, body.toString(Charsets.UTF_8))
    }

    private fun exactly(input: InputStream, n: Int): ByteArray {
        if (n > MAX_BODY) throw IOException("response too large")
        val bytes = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = input.read(bytes, read, n - read)
            if (r < 0) throw IOException("truncated")
            read += r
        }
        return bytes
    }

    private fun line(input: InputStream): String? {
        val text = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == -1) return if (text.isEmpty()) null else text.toString()
            if (c == '\n'.code) return text.toString().trimEnd('\r')
            if (text.length > 8_192) throw IOException("line too long")
            text.append(c.toChar())
        }
    }

    private const val MAX_BODY = 4 shl 20
}
