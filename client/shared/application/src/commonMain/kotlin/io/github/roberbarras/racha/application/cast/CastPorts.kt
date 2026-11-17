package io.github.roberbarras.racha.application.cast

import io.github.roberbarras.racha.application.playback.EngineState
import io.github.roberbarras.racha.domain.cast.CastDevice
import kotlinx.coroutines.flow.Flow

/** Finds Chromecasts and DLNA renderers on the local network while collected (nothing is searched otherwise). */
fun interface CastDiscovery {
    fun devices(): Flow<List<CastDevice>>
}

/** What a device is asked to play: URLs it can fetch from this machine (see [MediaPublisher]). */
data class RemoteMedia(
    val url: String,
    val contentType: String,
    val title: String,
    val subtitle: RemoteSubtitle?,
    val startMs: Long,
)

data class RemoteSubtitle(val url: String, val language: String, val label: String)

/** The device's state, read when asked ([RemotePlayer.status]). */
data class RemoteStatus(
    val state: EngineState,
    val playing: Boolean,
    val positionMs: Long,
    val durationMs: Long?,
    val speed: Float = 1f,
    val error: String? = null,
)

/**
 * A device being played on (one per casting session). Calls may block on the network: they are suspending. Subtitles
 * are chosen when loading (changing them reloads at the same position), the one way every device supports.
 */
interface RemotePlayer {
    /** Whether the playback speed can be changed (Chromecast yes, most TVs no). */
    val supportsSpeed: Boolean

    suspend fun load(media: RemoteMedia, play: Boolean)

    suspend fun play()

    suspend fun pause()

    suspend fun seek(positionMs: Long)

    suspend fun setSpeed(speed: Float)

    suspend fun status(): RemoteStatus

    /** Stops playing on the device and lets it go back to idle. */
    suspend fun stop()

    fun close()
}

fun interface CastConnector {
    suspend fun connect(device: CastDevice): RemotePlayer
}

/**
 * Serves files to devices on the local network while casting: only what is published, under unguessable URLs, from
 * the address of this machine that [forHost] can reach. [stop] closes everything.
 */
interface MediaPublisher {
    /** A file (`file://…`) served as is, with byte ranges (seeking). */
    suspend fun publishFile(uri: String, contentType: String, forHost: String): String

    /** A subtitle file (`.srt`) served as WebVTT, which Chromecasts read. */
    suspend fun publishSubtitle(uri: String, forHost: String): String

    fun stop()
}
