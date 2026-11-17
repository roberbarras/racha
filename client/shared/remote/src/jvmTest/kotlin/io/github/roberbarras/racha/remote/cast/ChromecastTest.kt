package io.github.roberbarras.racha.remote.cast

import io.github.roberbarras.racha.application.cast.RemoteMedia
import io.github.roberbarras.racha.application.cast.RemoteSubtitle
import io.github.roberbarras.racha.application.playback.EngineState
import io.github.roberbarras.racha.domain.cast.CastDevice
import io.github.roberbarras.racha.domain.cast.CastKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Cast v2 against a simulated Chromecast (H18): never a real device in tests. */
class ChromecastTest {
    /** A Default Media Receiver as far as Racha uses it. Plain TCP: TLS is the only thing not exercised here. */
    private class FakeReceiver : AutoCloseable {
        val server = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
        val received: MutableList<Pair<String, JsonObject>> = Collections.synchronizedList(mutableListOf())
        @Volatile var state = "IDLE"
        @Volatile var time = 0.0
        @Volatile var out: DataOutputStream? = null

        init {
            thread(isDaemon = true) {
                val socket = runCatching { server.accept() }.getOrNull() ?: return@thread
                val input = DataInputStream(socket.getInputStream())
                out = DataOutputStream(socket.getOutputStream())
                runCatching {
                    while (true) {
                        val message = CastMessage.decode(ByteArray(input.readInt()).also(input::readFully))
                        val json = Json.parseToJsonElement(message.payload).jsonObject
                        received += message.namespace to json
                        val type = json["type"]?.jsonPrimitive?.content
                        val id = json["requestId"]?.jsonPrimitive?.int ?: 0
                        when (type) {
                            "LAUNCH" -> reply(message, CastChannel.RECEIVER_NS, """{"type":"RECEIVER_STATUS","requestId":$id,"status":{"applications":[{"appId":"CC1AD845","transportId":"web-7","sessionId":"s-1"}]}}""")
                            "LOAD" -> { state = "BUFFERING"; time = json["currentTime"]!!.jsonPrimitive.content.toDouble(); reply(message, CastChannel.MEDIA, media(id)) }
                            "PLAY" -> { state = "PLAYING"; reply(message, CastChannel.MEDIA, media(id)) }
                            "PAUSE" -> { state = "PAUSED"; reply(message, CastChannel.MEDIA, media(id)) }
                            "SEEK" -> { time = json["currentTime"]!!.jsonPrimitive.content.toDouble(); reply(message, CastChannel.MEDIA, media(id)) }
                            "SET_PLAYBACK_RATE", "GET_STATUS" -> reply(message, CastChannel.MEDIA, media(id))
                            "STOP" -> reply(message, message.namespace, if (message.namespace == CastChannel.MEDIA) """{"type":"MEDIA_STATUS","requestId":$id,"status":[]}""" else """{"type":"RECEIVER_STATUS","requestId":$id,"status":{}}""")
                        }
                    }
                }
            }
        }

        fun media(id: Int) = if (state == "GONE") """{"type":"MEDIA_STATUS","requestId":$id,"status":[]}"""
            else """{"type":"MEDIA_STATUS","requestId":$id,"status":[{"mediaSessionId":7,"playerState":"$state","currentTime":$time,"playbackRate":1.5,"media":{"duration":600.0}}]}"""

        @Synchronized
        fun send(source: String, namespace: String, payload: String) {
            val bytes = CastMessage(source, "sender-0", namespace, payload).encode()
            out!!.writeInt(bytes.size); out!!.write(bytes); out!!.flush()
        }

        private fun reply(to: CastMessage, namespace: String, payload: String) = send(to.destination, namespace, payload)

        override fun close() = server.close()
    }

    private val receiver = FakeReceiver()

    @AfterTest
    fun cleanUp() = receiver.close()

    @Test
    fun messagesSurviveTheWire() {
        val message = CastMessage("sender-0", "receiver-0", CastChannel.RECEIVER_NS, """{"type":"LAUNCH","title":"Políticas ñ"}""" + "x".repeat(300))
        assertEquals(message, CastMessage.decode(message.encode()))
    }

    @Test
    fun aLessonIsLoadedWithItsSubtitleDrivenAndFinishes() = runBlocking<Unit> {
        val connector = ChromecastConnector { host, port -> Socket(host, port) }
        val device = CastDevice("cc", "Salón", CastKind.CHROMECAST, "127.0.0.1:${receiver.server.localPort}")
        val player = connector.connect(device)
        player.load(RemoteMedia("http://10.0.0.5:4000/t/v.mp4", "video/mp4", "IAM", RemoteSubtitle("http://10.0.0.5:4000/t/v.vtt", "es", "Español"), 754_000), play = true)

        val load = receiver.received.first { it.second["type"]?.jsonPrimitive?.content == "LOAD" }.second
        assertEquals("s-1", load["sessionId"]!!.jsonPrimitive.content)
        assertEquals(754.0, load["currentTime"]!!.jsonPrimitive.content.toDouble())
        val media = load["media"]!!.jsonObject
        assertEquals("http://10.0.0.5:4000/t/v.mp4", media["contentId"]!!.jsonPrimitive.content)
        assertEquals("text/vtt", media["tracks"]!!.jsonArray[0].jsonObject["trackContentType"]!!.jsonPrimitive.content)
        assertEquals(listOf(1), load["activeTrackIds"]!!.jsonArray.map { it.jsonPrimitive.int })

        assertEquals(EngineState.BUFFERING, player.status().state)
        player.play()
        player.seek(800_000)
        val playing = player.status()
        assertEquals(EngineState.READY, playing.state)
        assertTrue(playing.playing)
        assertEquals(800_000, playing.positionMs)
        assertEquals(600_000, playing.durationMs)
        player.pause()
        assertEquals(false, player.status().playing)
        player.setSpeed(1.5f)

        // The receiver announces the end by itself, then reports nothing loaded: still "ended".
        receiver.send("web-7", CastChannel.MEDIA, """{"type":"MEDIA_STATUS","requestId":0,"status":[{"mediaSessionId":7,"playerState":"IDLE","idleReason":"FINISHED","currentTime":600.0,"media":{"duration":600.0}}]}""")
        delay(200)
        receiver.state = "GONE"
        assertEquals(EngineState.ENDED, player.status().state)

        player.stop()
        player.close()
        val types = receiver.received.map { it.second["type"]?.jsonPrimitive?.contentOrNull }
        assertEquals(listOf("CONNECT", "LAUNCH", "CONNECT", "LOAD"), types.filter { it != "PING" }.take(4))
        assertTrue(receiver.received.any { (ns, json) -> ns == CastChannel.RECEIVER_NS && json["type"]?.jsonPrimitive?.content == "STOP" }, "the receiver app is closed")
        assertEquals(JsonPrimitive(7), receiver.received.last { it.second["type"]?.jsonPrimitive?.content == "PAUSE" }.second["mediaSessionId"])
    }
}
