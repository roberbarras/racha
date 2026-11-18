package io.github.roberbarras.racha.remote.cast

import io.github.roberbarras.racha.application.cast.MediaPublisher
import io.github.roberbarras.racha.application.shared.AppLog
import io.github.roberbarras.racha.domain.cast.CastMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLEncoder
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The files being cast, served to the TV over HTTP (H18). Only while casting, only what was published, under a random
 * 128-bit path, on the one address of this machine the TV can reach (never on every interface). Byte ranges for
 * seeking, `HEAD`, CORS for Chromecast subtitles and the DLNA headers TVs expect. A plain socket server: it runs the
 * same on Android, which has no `com.sun.net.httpserver`.
 */
class LanMediaServer(
    /** The address of this machine that reaches [host]; tests use loopback. */
    private val localAddressFor: (String) -> InetAddress = ::routeTo,
) : MediaPublisher {
    private sealed interface Entry {
        val contentType: String
        class File(val path: Path, override val contentType: String) : Entry
        class Text(val bytes: ByteArray, override val contentType: String) : Entry
    }

    private val entries = ConcurrentHashMap<String, Entry>()
    private val random = SecureRandom()
    private var server: ServerSocket? = null
    private var pool: ExecutorService? = null
    private var acceptor: Thread? = null

    override suspend fun publishFile(uri: String, contentType: String, forHost: String): String = withContext(Dispatchers.IO) {
        val path = Path.of(URI(uri))
        require(Files.isRegularFile(path)) { "not a file: $uri" }
        publish(Entry.File(path, contentType), path.fileName.toString(), forHost)
    }

    override suspend fun publishSubtitle(uri: String, forHost: String): String = withContext(Dispatchers.IO) {
        val path = Path.of(URI(uri))
        val srt = decode(Files.readAllBytes(path))
        val name = path.fileName.toString().substringBeforeLast('.') + ".vtt"
        publish(Entry.Text(CastMedia.srtToVtt(srt).encodeToByteArray(), "text/vtt; charset=utf-8"), name, forHost)
    }

    @Synchronized
    private fun publish(entry: Entry, name: String, forHost: String): String {
        val socket = server ?: start(localAddressFor(forHost))
        val token = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        entries[token] = entry
        val host = socket.inetAddress.hostAddress.let { if (':' in it) "[$it]" else it }
        return "http://$host:${socket.localPort}/$token/" + URLEncoder.encode(name, Charsets.UTF_8).replace("+", "%20")
    }

    private fun start(address: InetAddress): ServerSocket {
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(address, 0), 16)
        // Wakes up every second: Java defers closing a listening socket while a thread is blocked in accept(), and until
        // then the system keeps accepting connections nobody serves. stop() waits for this thread, so once it returns
        // the port is really closed.
        socket.soTimeout = 1_000
        val workers = Executors.newCachedThreadPool { r -> Thread(r, "racha-cast-http").apply { isDaemon = true } }
        server = socket
        pool = workers
        acceptor = Thread({
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: java.net.SocketTimeoutException) {
                    continue
                } catch (_: IOException) {
                    break
                }
                try {
                    workers.execute { serve(client) }
                } catch (_: java.util.concurrent.RejectedExecutionException) {
                    runCatching { client.close() } // stopping
                }
            }
        }, "racha-cast-accept").apply { isDaemon = true; start() }
        AppLog.info("cast", "serving on ${socket.inetAddress.hostAddress}:${socket.localPort}")
        return socket
    }

    @Synchronized
    override fun stop() {
        entries.clear()
        runCatching { server?.close() }
        acceptor?.join(3_000)
        pool?.shutdownNow()
        server = null
        pool = null
        acceptor = null
    }

    /** One request per connection (`Connection: close`): TVs reconnect for every range anyway. */
    private fun serve(socket: Socket) = socket.use {
        runCatching {
            socket.soTimeout = 30_000
            val input = BufferedInputStream(socket.getInputStream())
            val requestLine = readLine(input) ?: return@runCatching
            val headers = HashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
            val parts = requestLine.split(' ')
            val method = parts.getOrNull(0) ?: ""
            val token = parts.getOrNull(1)?.removePrefix("/")?.substringBefore('/') ?: ""
            val out = socket.getOutputStream()
            val entry = entries[token]
            when {
                method != "GET" && method != "HEAD" -> respond(out, 405, "Method Not Allowed", emptyMap(), null)
                entry == null -> respond(out, 404, "Not Found", emptyMap(), null)
                else -> send(out, entry, headers["range"], head = method == "HEAD")
            }
        }.onFailure { if (it !is IOException) AppLog.warn("cast", "serving a request failed", it) }
    }

    private fun send(out: OutputStream, entry: Entry, range: String?, head: Boolean) {
        val length = when (entry) {
            is Entry.File -> Files.size(entry.path)
            is Entry.Text -> entry.bytes.size.toLong()
        }
        val common = linkedMapOf(
            "Content-Type" to entry.contentType,
            "Accept-Ranges" to "bytes",
            "Access-Control-Allow-Origin" to "*",
            "transferMode.dlna.org" to "Streaming",
            "contentFeatures.dlna.org" to "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000",
        )
        val wanted = parseRange(range, length)
        if (range != null && wanted == null) {
            respond(out, 416, "Range Not Satisfiable", common + ("Content-Range" to "bytes */$length"), null)
            return
        }
        val (from, to) = wanted ?: (0L to length - 1)
        val count = (to - from + 1).coerceAtLeast(0)
        val headers = common + ("Content-Length" to count.toString()) +
            (if (wanted != null) mapOf("Content-Range" to "bytes $from-$to/$length") else emptyMap())
        respond(out, if (wanted != null) 206 else 200, if (wanted != null) "Partial Content" else "OK", headers, null)
        if (head || count == 0L) return
        when (entry) {
            is Entry.Text -> out.write(entry.bytes, from.toInt(), count.toInt())
            is Entry.File -> FileChannel.open(entry.path, StandardOpenOption.READ).use { channel ->
                val target = Channels.newChannel(out)
                var position = from
                val end = from + count
                while (position < end) {
                    val sent = channel.transferTo(position, end - position, target)
                    if (sent <= 0) break
                    position += sent
                }
            }
        }
        out.flush()
    }

    private fun respond(out: OutputStream, code: Int, reason: String, headers: Map<String, String>, body: ByteArray?) {
        val text = buildString {
            append("HTTP/1.1 $code $reason\r\n")
            headers.forEach { (k, v) -> append(k).append(": ").append(v).append("\r\n") }
            if (body == null && "Content-Length" !in headers) append("Content-Length: 0\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(text.toByteArray(Charsets.ISO_8859_1))
        body?.let(out::write)
        out.flush()
    }

    companion object {
        /** `bytes=a-b`, `bytes=a-` or `bytes=-n` within [length]; null if absent or unsatisfiable. */
        internal fun parseRange(header: String?, length: Long): Pair<Long, Long>? {
            val spec = header?.trim()?.takeIf { it.startsWith("bytes=") }?.removePrefix("bytes=")?.substringBefore(',')?.trim() ?: return null
            val start = spec.substringBefore('-').trim()
            val end = spec.substringAfter('-').trim()
            if (length <= 0) return null
            return when {
                start.isEmpty() -> end.toLongOrNull()?.takeIf { it > 0 }?.let { n -> (length - n).coerceAtLeast(0) to length - 1 }
                else -> {
                    val from = start.toLongOrNull() ?: return null
                    val to = (end.toLongOrNull() ?: (length - 1)).coerceAtMost(length - 1)
                    if (from > to || from >= length) null else from to to
                }
            }
        }

        /** UTF-8 if it is valid UTF-8, else Windows-1252 (old subtitles). */
        internal fun decode(bytes: ByteArray): String {
            val decoder = Charsets.UTF_8.newDecoder()
            return runCatching { decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString() }.getOrElse { String(bytes, charset("windows-1252")) }
        }

        private fun readLine(input: InputStream): String? {
            val line = StringBuilder()
            while (true) {
                val c = input.read()
                if (c == -1) return if (line.isEmpty()) null else line.toString()
                if (c == '\n'.code) return line.toString().trimEnd('\r')
                if (line.length > 8_192) throw IOException("header line too long")
                line.append(c.toChar())
            }
        }

        /** Which local address the system would use to reach [host] (a UDP "connect" sends nothing). */
        fun routeTo(host: String): InetAddress = DatagramSocket().use { socket ->
            socket.connect(InetSocketAddress(InetAddress.getByName(host), 9))
            socket.localAddress
        }
    }
}
