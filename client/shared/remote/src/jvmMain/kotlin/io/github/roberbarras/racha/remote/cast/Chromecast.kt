package io.github.roberbarras.racha.remote.cast

import io.github.roberbarras.racha.application.cast.CastConnector
import io.github.roberbarras.racha.application.cast.RemoteMedia
import io.github.roberbarras.racha.application.cast.RemotePlayer
import io.github.roberbarras.racha.application.cast.RemoteStatus
import io.github.roberbarras.racha.application.playback.EngineState
import io.github.roberbarras.racha.domain.cast.CastDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Chromecasts through Cast v2 (H18), without Google Play services (it works the same on Linux): the Default Media
 * Receiver plays the published URL with its subtitle track. Chromecasts present self-signed certificates, so the TLS
 * of this connection (and only this one) accepts any: it carries playback commands on the local network, no secrets.
 */
class ChromecastConnector(
    private val open: (String, Int) -> Socket = ::tlsSocket,
) : CastConnector {
    override suspend fun connect(device: CastDevice): RemotePlayer = withContext(Dispatchers.IO) {
        val host = device.address.substringBeforeLast(':')
        val port = device.address.substringAfterLast(':').toIntOrNull() ?: 8009
        val channel = CastChannel(open(host, port))
        try {
            channel.connect(CastChannel.RECEIVER)
            val status = channel.request(CastChannel.RECEIVER, CastChannel.RECEIVER_NS, buildJsonObject {
                put("type", "LAUNCH"); put("appId", MEDIA_RECEIVER)
            }, timeoutMs = 20_000)
            val app = applicationOf(status) ?: throw IOException("the receiver did not start")
            channel.connect(app.transportId)
            ChromecastPlayer(channel, app.transportId, app.sessionId)
        } catch (e: Exception) {
            channel.close()
            throw e
        }
    }

    internal data class App(val transportId: String, val sessionId: String)

    companion object {
        /** Google's Default Media Receiver: plays MP4/WebM/HLS… with WebVTT subtitles, no registration needed. */
        const val MEDIA_RECEIVER = "CC1AD845"

        internal fun applicationOf(status: JsonObject): App? {
            val apps = (status["status"] as? JsonObject)?.get("applications") as? JsonArray ?: return null
            val app = apps.map { it.jsonObject }.firstOrNull { it.str("appId") == MEDIA_RECEIVER } ?: return null
            return App(app.str("transportId") ?: return null, app.str("sessionId") ?: "")
        }

        fun tlsSocket(host: String, port: Int): Socket {
            val trustAll = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trustAll), null) }
            val raw = Socket().apply { connect(InetSocketAddress(host, port), 5_000); soTimeout = 0 }
            return (context.socketFactory.createSocket(raw, host, port, true) as SSLSocket).apply { startHandshake() }
        }

        internal fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    }
}

class ChromecastPlayer internal constructor(
    private val channel: CastChannel,
    private val transportId: String,
    private val sessionId: String,
) : RemotePlayer {
    override val supportsSpeed: Boolean = true
    private var mediaSessionId: Int? = null
    /** The receiver says "finished" once (in a status it sends by itself), then reports nothing loaded. */
    @Volatile private var finished: RemoteStatus? = null

    init {
        channel.onUnsolicited = { namespace, message ->
            if (namespace == CastChannel.MEDIA) statusOf(message).takeIf { it.state == EngineState.ENDED }?.let { finished = it }
        }
    }

    override suspend fun load(media: RemoteMedia, play: Boolean) {
        val answer = channel.request(transportId, CastChannel.MEDIA, buildJsonObject {
            put("type", "LOAD")
            put("sessionId", sessionId)
            put("autoplay", play)
            put("currentTime", media.startMs / 1000.0)
            putJsonObject("media") {
                put("contentId", media.url)
                put("contentUrl", media.url)
                put("streamType", "BUFFERED")
                put("contentType", media.contentType)
                putJsonObject("metadata") { put("metadataType", 0); put("title", media.title) }
                media.subtitle?.let { s ->
                    putJsonArray("tracks") {
                        add(buildJsonObject {
                            put("trackId", 1); put("type", "TEXT"); put("subtype", "SUBTITLES")
                            put("trackContentId", s.url); put("trackContentType", "text/vtt")
                            put("name", s.label); put("language", s.language)
                        })
                    }
                }
            }
            if (media.subtitle != null) putJsonArray("activeTrackIds") { add(1) }
        }, timeoutMs = 30_000)
        finished = null
        when (answer.str("type")) {
            "MEDIA_STATUS" -> mediaSessionId = sessionOf(answer) ?: mediaSessionId
            else -> throw IOException("load: ${answer.str("type")} ${answer.str("reason") ?: ""}".trim())
        }
    }

    override suspend fun play() = command("PLAY")
    override suspend fun pause() = command("PAUSE")
    override suspend fun seek(positionMs: Long) = command("SEEK") { put("currentTime", positionMs / 1000.0) }
    override suspend fun setSpeed(speed: Float) = command("SET_PLAYBACK_RATE") { put("playbackRate", speed.toDouble()) }

    override suspend fun status(): RemoteStatus {
        val answer = channel.request(transportId, CastChannel.MEDIA, buildJsonObject {
            put("type", "GET_STATUS"); mediaSessionId?.let { put("mediaSessionId", it) }
        })
        sessionOf(answer)?.let { mediaSessionId = it }
        val status = statusOf(answer)
        return if (status.state == EngineState.IDLE) finished ?: status else status
    }

    override suspend fun stop() {
        runCatching { command("STOP") }
        runCatching {
            channel.request(CastChannel.RECEIVER, CastChannel.RECEIVER_NS, buildJsonObject { put("type", "STOP"); put("sessionId", sessionId) }, 5_000)
        }
    }

    override fun close() = channel.close()

    private suspend fun command(type: String, extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}) {
        val session = mediaSessionId ?: run { delay(200); mediaSessionId } ?: throw IOException("nothing loaded")
        val answer = channel.request(transportId, CastChannel.MEDIA, buildJsonObject {
            put("type", type); put("mediaSessionId", session); extra()
        })
        if (answer.str("type") != "MEDIA_STATUS") throw IOException("$type: ${answer.str("type")}")
    }

    companion object {
        private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull

        private fun first(answer: JsonObject): JsonObject? = (answer["status"] as? JsonArray)?.firstOrNull() as? JsonObject

        internal fun sessionOf(answer: JsonObject): Int? = first(answer)?.let { (it["mediaSessionId"] as? JsonPrimitive)?.intOrNull }

        /** A `MEDIA_STATUS` as engine terms: finished → ended, error → error, nothing loaded → idle. */
        internal fun statusOf(answer: JsonObject): RemoteStatus {
            val status = first(answer) ?: return RemoteStatus(EngineState.IDLE, false, 0, null)
            val position = (((status["currentTime"] as? JsonPrimitive)?.doubleOrNull ?: 0.0) * 1000).toLong()
            val duration = ((status["media"] as? JsonObject)?.get("duration") as? JsonPrimitive)?.doubleOrNull?.let { (it * 1000).toLong() }
            val speed = (status["playbackRate"] as? JsonPrimitive)?.doubleOrNull?.toFloat() ?: 1f
            return when (status.str("playerState")) {
                "PLAYING" -> RemoteStatus(EngineState.READY, true, position, duration, speed)
                "PAUSED" -> RemoteStatus(EngineState.READY, false, position, duration, speed)
                "BUFFERING", "LOADING" -> RemoteStatus(EngineState.BUFFERING, true, position, duration, speed)
                else -> when (status.str("idleReason")) {
                    "FINISHED" -> RemoteStatus(EngineState.ENDED, false, duration ?: position, duration, speed)
                    "ERROR" -> RemoteStatus(EngineState.ERROR, false, position, duration, speed, "cast_error")
                    else -> RemoteStatus(EngineState.IDLE, false, position, duration, speed)
                }
            }
        }
    }
}
