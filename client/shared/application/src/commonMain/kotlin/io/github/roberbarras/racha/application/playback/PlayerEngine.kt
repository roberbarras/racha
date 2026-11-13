package io.github.roberbarras.racha.application.playback

import kotlinx.coroutines.flow.StateFlow

/** External `.srt` attached to a video (spec §4.1); [id] is chosen by the caller and used to select it. */
data class SubtitleSource(val id: String, val uri: String, val language: String, val label: String)

data class MediaSource(val uri: String, val subtitles: List<SubtitleSource>)

data class AudioTrack(val id: String, val language: String?, val label: String?)

enum class EngineState { IDLE, BUFFERING, READY, ENDED, ERROR }

/** Event-driven engine state. The position is not here: it changes continuously and is read on demand. */
data class EngineSnapshot(
    val state: EngineState = EngineState.IDLE,
    val playing: Boolean = false,
    val durationMs: Long? = null,
    val speed: Float = 1f,
    val audioTracks: List<AudioTrack> = emptyList(),
    val selectedAudio: String? = null,
    val selectedSubtitle: String? = null,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val error: String? = null,
)

/**
 * Outbound port implemented by each platform's media engine (Media3 on Android, libmpv on desktop). Calls happen on
 * the UI thread; [snapshot] emits on state changes only, so nothing ticks while nobody needs the position.
 */
interface PlayerEngine {
    val snapshot: StateFlow<EngineSnapshot>

    fun positionMs(): Long

    /** Loads [source] positioned at [startMs]; starts playing if [playWhenReady]. */
    fun load(source: MediaSource, startMs: Long, playWhenReady: Boolean)

    fun play()

    fun pause()

    fun seekTo(positionMs: Long)

    fun setSpeed(speed: Float)

    /** Selects one of the [MediaSource.subtitles] by id, or turns subtitles off with null. */
    fun selectSubtitle(id: String?)

    fun selectAudio(id: String?)

    fun release()
}
