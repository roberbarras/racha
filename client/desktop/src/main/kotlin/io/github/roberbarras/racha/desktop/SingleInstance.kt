package io.github.roberbarras.racha.desktop

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * One Racha per user session: a second launch (e.g. a `racha://` link opened from the browser or a notification) hands
 * its link to the running app through a Unix socket and exits, so two windows never share the same database.
 * An empty message just brings the window to the front.
 */
class SingleInstance private constructor(private val server: ServerSocketChannel, private val path: Path) : AutoCloseable {
    private val messages = Channel<String>(Channel.BUFFERED)

    /** Links from later launches ("" = only focus). */
    val received: Flow<String> = messages.receiveAsFlow()

    private val listener = Thread({
        while (server.isOpen) {
            val client = runCatching { server.accept() }.getOrNull() ?: break
            client.use { messages.trySend(read(it)) }
        }
    }, "racha-single-instance").apply { isDaemon = true; start() }

    override fun close() {
        runCatching { server.close() }
        runCatching { Files.deleteIfExists(path) }
    }

    companion object {
        private const val MAX_MESSAGE = 4096

        /** `$XDG_RUNTIME_DIR/racha.sock` (private to the user), or a per-user file in the temp folder. */
        fun defaultPath(): Path = System.getenv("XDG_RUNTIME_DIR")?.takeIf { it.isNotBlank() }?.let { Path.of(it, "racha.sock") }
            ?: Path.of(System.getProperty("java.io.tmpdir"), "racha-${System.getProperty("user.name")}.sock")

        /**
         * Becomes the running instance, or, if one is already running, sends it [message] and returns null (the caller
         * exits). A socket file left by a crashed instance is replaced.
         */
        fun claim(message: String, path: Path = defaultPath()): SingleInstance? {
            if (send(path, message)) return null
            Files.deleteIfExists(path)
            val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
            return try {
                server.bind(UnixDomainSocketAddress.of(path))
                SingleInstance(server, path)
            } catch (e: IOException) {
                server.close()
                throw e
            }
        }

        private fun send(path: Path, message: String): Boolean {
            if (!Files.exists(path)) return false
            return try {
                SocketChannel.open(UnixDomainSocketAddress.of(path)).use { channel ->
                    channel.write(ByteBuffer.wrap(message.take(MAX_MESSAGE).toByteArray(StandardCharsets.UTF_8)))
                }
                true
            } catch (e: IOException) {
                false // nobody listening: a stale file
            }
        }

        private fun read(channel: SocketChannel): String {
            val buffer = ByteBuffer.allocate(MAX_MESSAGE)
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) Unit
            return String(buffer.array(), 0, buffer.position(), StandardCharsets.UTF_8)
        }
    }
}
