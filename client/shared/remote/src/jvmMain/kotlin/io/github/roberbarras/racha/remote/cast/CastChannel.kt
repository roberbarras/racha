package io.github.roberbarras.racha.remote.cast

import io.github.roberbarras.racha.application.shared.AppLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** One Cast v2 message: the protobuf `CastMessage` with a UTF-8 JSON payload (the only kind the media app uses). */
data class CastMessage(val source: String, val destination: String, val namespace: String, val payload: String) {
    fun encode(): ByteArray {
        val out = ByteArrayOutputStream()
        fun varint(value: Long) {
            var v = value
            while (v and 0x7F.inv().toLong() != 0L) { out.write(((v and 0x7F) or 0x80).toInt()); v = v ushr 7 }
            out.write(v.toInt())
        }
        fun string(field: Int, text: String) {
            val bytes = text.encodeToByteArray()
            varint((field shl 3 or 2).toLong()); varint(bytes.size.toLong()); out.write(bytes)
        }
        varint((1 shl 3).toLong()); varint(0) // protocol_version = CASTV2_1_0
        string(2, source)
        string(3, destination)
        string(4, namespace)
        varint((5 shl 3).toLong()); varint(0) // payload_type = STRING
        string(6, payload)
        return out.toByteArray()
    }

    companion object {
        fun decode(bytes: ByteArray): CastMessage {
            var i = 0
            fun varint(): Long {
                var shift = 0
                var result = 0L
                while (true) {
                    val b = bytes[i++].toInt() and 0xFF
                    result = result or ((b and 0x7F).toLong() shl shift)
                    if (b and 0x80 == 0) return result
                    shift += 7
                    if (shift > 63) throw IOException("bad varint")
                }
            }
            val strings = HashMap<Int, String>()
            while (i < bytes.size) {
                val key = varint().toInt()
                when (key and 7) {
                    0 -> varint()
                    2 -> {
                        val length = varint().toInt()
                        if (length < 0 || i + length > bytes.size) throw IOException("bad length")
                        strings[key ushr 3] = bytes.decodeToString(i, i + length)
                        i += length
                    }
                    else -> throw IOException("unexpected wire type ${key and 7}")
                }
            }
            return CastMessage(strings[2] ?: "", strings[3] ?: "", strings[4] ?: "", strings[6] ?: "")
        }
    }
}

/**
 * A Cast v2 connection: length-prefixed messages both ways, answers matched by `requestId`, heartbeat every 5 s (a
 * Chromecast drops silent connections), and the latest unsolicited status per namespace kept for [latest].
 */
class CastChannel(private val socket: Socket) : AutoCloseable {
    private val input = DataInputStream(socket.getInputStream())
    private val output = DataOutputStream(socket.getOutputStream())
    private val ids = AtomicInteger(1)
    private val waiting = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()
    private val latest = ConcurrentHashMap<String, JsonObject>()
    private val heartbeat: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "racha-cast-ping").apply { isDaemon = true } }
    @Volatile private var closed = false

    /** Messages nobody asked for (`requestId` 0): statuses the receiver broadcasts, like "finished". */
    @Volatile var onUnsolicited: ((namespace: String, message: JsonObject) -> Unit)? = null

    init {
        Thread({ readLoop() }, "racha-cast-read").apply { isDaemon = true }.start()
        heartbeat.scheduleAtFixedRate({ runCatching { send(SENDER, RECEIVER, HEARTBEAT, """{"type":"PING"}""") } }, 5, 5, TimeUnit.SECONDS)
    }

    val isOpen: Boolean get() = !closed

    fun connect(destination: String) = send(SENDER, destination, CONNECTION, """{"type":"CONNECT","origin":{}}""")

    /** Sends [payload] (a `requestId` is added) and waits for the answer with the same id. */
    suspend fun request(destination: String, namespace: String, payload: JsonObject, timeoutMs: Long = 10_000): JsonObject {
        val id = ids.getAndIncrement()
        val answer = CompletableDeferred<JsonObject>()
        waiting[id] = answer
        try {
            val body = buildJsonObject { payload.forEach { (k, v) -> put(k, v) }; put("requestId", id) }
            withContext(Dispatchers.IO) { send(SENDER, destination, namespace, body.toString()) }
            return withTimeout(timeoutMs) { answer.await() }
        } finally {
            waiting.remove(id)
        }
    }

    /** The last message received on [namespace] (statuses are also broadcast without being asked). */
    fun latest(namespace: String): JsonObject? = latest[namespace]

    @Synchronized
    fun send(source: String, destination: String, namespace: String, payload: String) {
        if (closed) throw IOException("closed")
        val bytes = CastMessage(source, destination, namespace, payload).encode()
        output.writeInt(bytes.size)
        output.write(bytes)
        output.flush()
    }

    private fun readLoop() {
        try {
            while (!closed) {
                val length = input.readInt()
                if (length !in 0..MAX_MESSAGE) throw IOException("message of $length bytes")
                val message = CastMessage.decode(ByteArray(length).also(input::readFully))
                val json = runCatching { Json.parseToJsonElement(message.payload).jsonObject }.getOrNull() ?: continue
                val type = (json["type"] as? JsonPrimitive)?.content
                if (message.namespace == HEARTBEAT && type == "PING") {
                    runCatching { send(SENDER, message.source, HEARTBEAT, """{"type":"PONG"}""") }
                    continue
                }
                if (message.namespace == CONNECTION && type == "CLOSE") break
                latest[message.namespace] = json
                val id = (json["requestId"] as? JsonPrimitive)?.intOrNull
                if (id != null && id != 0) waiting[id]?.complete(json) else onUnsolicited?.invoke(message.namespace, json)
            }
        } catch (e: IOException) {
            if (!closed) AppLog.info("cast", "connection closed: ${e.message}")
        } finally {
            close()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        heartbeat.shutdownNow()
        waiting.values.forEach { it.completeExceptionally(IOException("closed")) }
        runCatching { socket.close() }
    }

    companion object {
        const val SENDER = "sender-0"
        const val RECEIVER = "receiver-0"
        const val CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
        const val HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
        const val RECEIVER_NS = "urn:x-cast:com.google.cast.receiver"
        const val MEDIA = "urn:x-cast:com.google.cast.media"
        private const val MAX_MESSAGE = 1 shl 20
    }
}
