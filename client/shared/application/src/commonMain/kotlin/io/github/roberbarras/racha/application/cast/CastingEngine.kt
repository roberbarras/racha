package io.github.roberbarras.racha.application.cast

import io.github.roberbarras.racha.application.playback.EngineSnapshot
import io.github.roberbarras.racha.application.playback.EngineState
import io.github.roberbarras.racha.application.playback.MediaSource
import io.github.roberbarras.racha.application.playback.PlayerEngine
import io.github.roberbarras.racha.application.shared.AppLog
import io.github.roberbarras.racha.domain.cast.CastDevice
import io.github.roberbarras.racha.domain.cast.CastKind
import io.github.roberbarras.racha.domain.cast.CastMedia
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** Where the video is playing. */
sealed interface CastState {
    data object Local : CastState
    data class Connecting(val device: CastDevice) : CastState
    data class Casting(val device: CastDevice) : CastState
    /** Could not start, or the connection was lost; playing here again. [reason]: `connect`, `lost`, `load`. */
    data class Failed(val device: CastDevice, val reason: String) : CastState
}

/**
 * The player's engine with "send to a TV" (H18): plays on this device ([local]) or on a Chromecast/DLNA renderer, and
 * moves between them keeping the position. The playback controller only sees a [PlayerEngine], so progress, history,
 * next/previous, bookmarks and statistics work the same while casting, and this device becomes the remote.
 *
 * Calls come from the UI thread, like every engine's; the device is driven in order on [scope] (which must run on
 * that thread too: [local] is touched from it) and, while casting, polled once a second for its state.
 */
class CastingEngine(
    private val local: PlayerEngine,
    private val connector: CastConnector,
    private val publisher: MediaPublisher,
    private val scope: CoroutineScope,
    private val pollMs: Long = 1_000,
    private val time: TimeSource = TimeSource.Monotonic,
) : PlayerEngine {
    private val state = MutableStateFlow(local.snapshot.value)
    override val snapshot: StateFlow<EngineSnapshot> = state.asStateFlow()

    private val mutableCast = MutableStateFlow<CastState>(CastState.Local)
    val cast: StateFlow<CastState> = mutableCast.asStateFlow()

    private class Remote(val device: CastDevice, val player: RemotePlayer)

    private var remote: Remote? = null
    private var source: MediaSource? = null
    /** What [local] has loaded: after casting it may be behind (the next lesson was loaded on the TV). */
    private var localSource: MediaSource? = null
    private var subtitle: String? = null
    private var speed = 1f
    private var last = RemoteStatus(EngineState.IDLE, false, 0, null)
    private var lastAt: TimeMark = time.markNow()
    /** Since the last load, whether the device has started it: an idle device before that is not "ended". */
    private var started = false

    private val commands = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private var mirror: Job? = null
    private var polling: Job? = null

    init {
        scope.launch { for (command in commands) command() }
        mirrorLocal()
    }

    val casting: Boolean get() = remote != null

    private fun mirrorLocal() {
        mirror?.cancel()
        mirror = scope.launch { local.snapshot.collect { if (remote == null) state.value = it } }
    }

    /** Runs [block] against the device in order; a failure means the device is gone. */
    private fun send(block: suspend (RemotePlayer) -> Unit) {
        commands.trySend {
            val r = remote ?: return@trySend
            try {
                block(r.player)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.warn("cast", "command failed on ${r.device.name}", e)
            }
        }
    }

    // ---- PlayerEngine ------------------------------------------------------------------------------------------

    override fun positionMs(): Long {
        if (remote == null) return local.positionMs()
        // Between reports the position moves on only while the device says it is playing (not while it loads).
        val moving = last.playing && last.state == EngineState.READY
        val elapsed = if (moving) (lastAt.elapsedNow().inWholeMilliseconds * last.speed).toLong() else 0
        return (last.positionMs + elapsed).let { p -> last.durationMs?.let { p.coerceAtMost(it) } ?: p }
    }

    override fun load(source: MediaSource, startMs: Long, playWhenReady: Boolean) {
        this.source = source
        subtitle = null
        val r = remote
        if (r == null) {
            localSource = source
            local.load(source, startMs, playWhenReady)
            return
        }
        assume(startMs, playWhenReady, EngineState.BUFFERING)
        started = false
        send { player -> player.load(media(r.device, source, startMs), playWhenReady) }
    }

    override fun play() {
        if (remote == null) return local.play()
        assume(positionMs(), true)
        send { it.play() }
    }

    override fun pause() {
        if (remote == null) return local.pause()
        assume(positionMs(), false)
        send { it.pause() }
    }

    override fun seekTo(positionMs: Long) {
        if (remote == null) return local.seekTo(positionMs)
        assume(positionMs, last.playing)
        send { it.seek(positionMs) }
    }

    override fun setSpeed(speed: Float) {
        this.speed = speed
        local.setSpeed(speed)
        val r = remote ?: return
        if (r.player.supportsSpeed) send { it.setSpeed(speed) }
    }

    override fun selectSubtitle(id: String?) {
        subtitle = id
        local.selectSubtitle(id)
        val r = remote ?: return
        val current = source ?: return
        // Every device takes subtitles with the media: load again where it is.
        state.value = state.value.copy(selectedSubtitle = id)
        val at = positionMs()
        val playing = last.playing
        started = false
        send { player -> player.load(media(r.device, current, at), playing) }
    }

    override fun selectAudio(id: String?) = local.selectAudio(id)

    override fun setVideoEnabled(enabled: Boolean) = local.setVideoEnabled(enabled)

    override fun release() {
        val r = remote
        remote = null
        polling?.cancel()
        mirror?.cancel()
        commands.close()
        if (r != null) {
            scope.launch {
                withContext(NonCancellable) {
                    runCatching { r.player.stop() }
                    r.player.close()
                    publisher.stop()
                }
            }
        }
        local.release()
    }

    // ---- casting -----------------------------------------------------------------------------------------------

    /** Moves playback to [device] where it is now; stays here (and says why in [cast]) if it cannot. */
    fun castTo(device: CastDevice) {
        val current = source ?: return
        if (remote?.device?.id == device.id) return
        commands.trySend {
            remote?.let { leave(it) }
            mutableCast.value = CastState.Connecting(device)
            val at = local.positionMs()
            val playing = local.snapshot.value.playing
            val player = try {
                connector.connect(device)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.warn("cast", "could not connect to ${device.name}", e)
                mutableCast.value = CastState.Failed(device, "connect")
                return@trySend
            }
            try {
                player.load(media(device, current, at), playing)
                if (speed != 1f && player.supportsSpeed) player.setSpeed(speed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.warn("cast", "${device.name} could not load the video", e)
                player.close()
                publisher.stop()
                mutableCast.value = CastState.Failed(device, "load")
                return@trySend
            }
            local.pause()
            remote = Remote(device, player)
            started = false
            assume(at, playing, EngineState.BUFFERING)
            mutableCast.value = CastState.Casting(device)
            poll()
        }
    }

    /** Back to this device, where the TV was (paused: the user is probably somewhere else). */
    fun stopCasting() {
        commands.trySend {
            val r = remote ?: return@trySend
            leave(r)
            mutableCast.value = CastState.Local
        }
    }

    private suspend fun leave(r: Remote) {
        val at = positionMs()
        remote = null
        polling?.cancel()
        runCatching { r.player.stop() }
        r.player.close()
        publisher.stop()
        val current = source
        if (current != null && current != localSource) {
            localSource = current
            local.load(current, at, playWhenReady = false)
            subtitle?.let { local.selectSubtitle(it) }
        } else {
            local.seekTo(at)
            local.pause()
        }
        mirrorLocal()
    }

    private fun poll() {
        polling?.cancel()
        polling = scope.launch {
            var failures = 0
            while (true) {
                delay(pollMs)
                val r = remote ?: break
                val status = try {
                    r.player.status().also { failures = 0 }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (++failures >= LOST_AFTER) {
                        AppLog.warn("cast", "lost ${r.device.name}", e)
                        commands.trySend {
                            if (remote === r) {
                                leave(r)
                                mutableCast.value = CastState.Failed(r.device, "lost")
                            }
                        }
                        break
                    }
                    continue
                }
                if (remote !== r) break
                apply(status)
            }
        }
    }

    private fun apply(status: RemoteStatus) {
        if (status.state == EngineState.BUFFERING || status.state == EngineState.READY) started = true
        // An idle device that has not started what was just loaded is not at its end.
        val effective = if (status.state == EngineState.ENDED && !started) status.copy(state = EngineState.BUFFERING) else status
        last = effective
        lastAt = time.markNow()
        val local = state.value
        state.value = local.copy(
            state = effective.state,
            playing = effective.playing,
            durationMs = effective.durationMs ?: local.durationMs,
            speed = if (remote?.player?.supportsSpeed == true) effective.speed else 1f,
            subtitleText = null,
            error = effective.error,
            selectedSubtitle = subtitle,
        )
    }

    /** What the UI shows right after a command, before the device reports back. */
    private fun assume(positionMs: Long, playing: Boolean, engineState: EngineState = last.state) {
        last = last.copy(positionMs = positionMs, playing = playing, state = engineState)
        lastAt = time.markNow()
        state.value = state.value.copy(playing = playing, state = engineState, selectedSubtitle = subtitle, subtitleText = null)
    }

    private suspend fun media(device: CastDevice, source: MediaSource, startMs: Long): RemoteMedia {
        val name = source.uri.substringAfterLast('/')
        val url = publisher.publishFile(source.uri, CastMedia.contentType(name), device.host)
        val sub = source.subtitles.firstOrNull { it.id == subtitle }?.let { s ->
            // Chromecasts read WebVTT; DLNA TVs that show external subtitles read SubRip.
            val url = when (device.kind) {
                CastKind.CHROMECAST -> publisher.publishSubtitle(s.uri, device.host)
                CastKind.DLNA -> publisher.publishFile(s.uri, CastMedia.contentType(s.uri), device.host)
            }
            RemoteSubtitle(url, s.language, s.label)
        }
        return RemoteMedia(url, CastMedia.contentType(name), title(name), sub, startMs)
    }

    private fun title(fileName: String): String =
        decodePercent(fileName).substringBeforeLast('.').replace(Regex("^\\d{1,3}[ ._-]+"), "").ifBlank { fileName }

    companion object {
        const val LOST_AFTER = 5

        /** `%20` and friends in a file URI, as UTF-8. */
        fun decodePercent(text: String): String {
            val bytes = ArrayList<Byte>(text.length)
            var i = 0
            while (i < text.length) {
                val hex = if (text[i] == '%' && i + 2 < text.length + 1 && i + 2 <= text.lastIndex) text.substring(i + 1, i + 3).toIntOrNull(16) else null
                if (hex != null) {
                    bytes += hex.toByte(); i += 3
                } else {
                    text[i].toString().encodeToByteArray().forEach(bytes::add); i++
                }
            }
            return bytes.toByteArray().decodeToString()
        }
    }
}
