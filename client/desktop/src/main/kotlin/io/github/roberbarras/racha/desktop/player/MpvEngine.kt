package io.github.roberbarras.racha.desktop.player

import io.github.roberbarras.racha.application.playback.AudioTrack
import io.github.roberbarras.racha.application.playback.EngineSnapshot
import io.github.roberbarras.racha.application.playback.EngineState
import io.github.roberbarras.racha.application.playback.MediaSource
import io.github.roberbarras.racha.application.playback.PlayerEngine
import io.github.roberbarras.racha.desktop.mpv.LibMpv
import io.github.roberbarras.racha.domain.preferences.VideoScale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URI
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * [PlayerEngine] on libmpv (ADR-0011). mpv presents by itself into a native X11 child window ([attach] with its id),
 * so frames never go through Compose; [attachHeadless] runs without video output (tests). Hardware decoding is off
 * unless the user enables it (it hung the GPU of the reference machine).
 *
 * The core is initialised only once the output is known; commands issued before that are kept and replayed.
 * One daemon thread waits for mpv events and turns observed properties into [snapshot] updates; nothing polls.
 */
class MpvEngine(hardwareDecoding: Boolean = false, audio: Boolean = true) : PlayerEngine {
    private val mpv = LibMpv()
    private val state = MutableStateFlow(EngineSnapshot())
    private val lock = Any()

    /** Calls from other threads hold the read lock; destroying the core takes the write lock. */
    private val gate = ReentrantReadWriteLock()
    @Volatile private var destroyed = false
    @Volatile private var initialized = false
    @Volatile private var closing = false
    private var thread: Thread? = null
    private var pendingLoad: Triple<MediaSource, Long, Boolean>? = null

    // Event-thread state, published as snapshots.
    private var loading = false
    private var loaded = false
    private var error: String? = null
    private var paused = true
    private var eof = false
    private var durationMs: Long? = null
    private var speed = 1f
    private var width = 0
    private var height = 0
    private var subText: String? = null
    private var sid: String? = null
    private var aid: String? = null
    private var audioTracks: List<AudioTrack> = emptyList()

    // Our subtitle ids ↔ mpv track ids, rebuilt for every file.
    @Volatile private var subtitles: List<Pair<String, String>> = emptyList() // (our id, path)
    @Volatile private var subtitleTracks: Map<String, String> = emptyMap() // our id → mpv id
    @Volatile private var wantedSubtitle: String? = null

    override val snapshot: StateFlow<EngineSnapshot> = state.asStateFlow()

    init {
        mpv.setOption("config", "no") // never read the user's mpv.conf: the app owns every option
        mpv.setOption("terminal", "no")
        mpv.setOption("idle", "yes")
        mpv.setOption("keep-open", "yes") // stay on the last frame at the end; "eof-reached" tells us it ended
        mpv.setOption("force-window", "no")
        mpv.setOption("osc", "no")
        mpv.setOption("osd-level", "0")
        mpv.setOption("load-scripts", "no")
        mpv.setOption("ytdl", "no") // fully offline
        mpv.setOption("input-default-bindings", "no")
        mpv.setOption("input-vo-keyboard", "no")
        mpv.setOption("input-cursor", "no") // pointer and keys go to the Compose overlay
        mpv.setOption("sub-auto", "no") // subtitles are the ones the library indexed, added explicitly
        mpv.setOption("audio-file-auto", "no")
        mpv.setOption("sub-visibility", "no") // drawn by the shared UI from "sub-text", like on Android
        mpv.setOption("sid", "no")
        mpv.setOption("hwdec", if (hardwareDecoding) "auto-safe" else "no")
        mpv.setOption("screenshot-format", "png")
        // -Dracha.mpv.ao=null silences test runs (harnesses play clips with a test tone through the real app).
        val audioOutput = if (audio) System.getProperty("racha.mpv.ao") else "null"
        audioOutput?.let { mpv.setOption("ao", it) }
    }

    /**
     * Video goes into the X11 window [windowId] (a heavyweight AWT component's peer). A later call with a new window
     * (the canvas was recreated) moves the output there: video off, new `wid`, video back on.
     */
    fun attach(windowId: Long) {
        if (initialized) {
            X11.withToolkitLock {
                guarded(Unit) {
                    mpv.setProperty("vid", "no")
                    awaitNoVideoOutput()
                }
                X11.restoreToolkitHandler()
            }
            guarded(Unit) {
                mpv.setProperty("wid", windowId.toString())
                mpv.setProperty("vid", "auto")
            }
            return
        }
        start { mpv.setOption("wid", windowId.toString()); mpv.setOption("vo", "gpu-next,gpu,x11") }
    }

    /** No video output at all: decoding, timing, audio and subtitles still work. */
    fun attachHeadless() = start { mpv.setOption("vo", "null") }

    /**
     * The output window is about to be destroyed: video off (position and audio stay for the final save), and wait
     * until mpv's video output is really gone, so it never touches a dead window and the Xlib error handler it
     * resets on the way out is back to AWT's before the window disappears (see X11). Bounded, called on the EDT.
     */
    fun detachVideo() {
        if (!initialized) return
        X11.withToolkitLock {
            guarded(Unit) {
                mpv.setProperty("vid", "no")
                awaitNoVideoOutput()
            }
            X11.restoreToolkitHandler()
        }
    }

    private fun awaitNoVideoOutput() {
        val deadline = System.nanoTime() + VO_SHUTDOWN_TIMEOUT_NS
        while (mpv.getProperty("current-vo") != null && System.nanoTime() < deadline) Thread.sleep(2)
    }

    private fun start(configureOutput: () -> Unit) {
        val replay = synchronized(lock) {
            if (initialized || closing) return
            configureOutput()
            mpv.initialize()
            OBSERVED.forEach { mpv.observeProperty(it, LibMpv.FORMAT_STRING) }
            mpv.observeProperty("track-list", LibMpv.FORMAT_NONE)
            initialized = true
            thread = Thread(::eventLoop, "mpv-events").apply { isDaemon = true; start() }
            pendingLoad.also { pendingLoad = null }
        }
        replay?.let { (source, startMs, play) -> load(source, startMs, play) }
    }

    @Volatile private var lastPositionMs = 0L

    override fun positionMs(): Long {
        if (!initialized) return pendingLoad?.second ?: 0
        return guarded(lastPositionMs) {
            (mpv.getProperty("time-pos")?.toDoubleOrNull()?.let { (it * 1000).toLong() } ?: lastPositionMs).also { lastPositionMs = it }
        }
    }

    /** Runs [block] against a live core, or returns [fallback] once it is being destroyed. */
    private inline fun <T> guarded(fallback: T, block: () -> T): T = gate.read { if (destroyed || closing) fallback else block() }

    override fun load(source: MediaSource, startMs: Long, playWhenReady: Boolean) {
        synchronized(lock) {
            if (!initialized) {
                pendingLoad = Triple(source, startMs, playWhenReady)
                return
            }
        }
        subtitles = source.subtitles.map { it.id to pathOf(it.uri) }
        subtitleTracks = emptyMap()
        wantedSubtitle = null
        guarded(Unit) {
            mpv.setProperty("sid", "no")
            mpv.setProperty("pause", if (playWhenReady) "no" else "yes")
            mpv.setProperty("start", String.format(Locale.ROOT, "%.3f", startMs / 1000.0))
            mpv.command("loadfile", pathOf(source.uri), "replace")
        }
    }

    override fun play() {
        if (!initialized) {
            pendingLoad = pendingLoad?.copy(third = true)
            return
        }
        guarded(Unit) {
            if (mpv.getProperty("eof-reached") == "yes") mpv.tryCommand("seek", "0", "absolute")
            mpv.setProperty("pause", "no")
        }
    }

    override fun pause() {
        if (!initialized) {
            pendingLoad = pendingLoad?.copy(third = false)
            return
        }
        guarded(Unit) { mpv.setProperty("pause", "yes") }
    }

    override fun seekTo(positionMs: Long) {
        if (!initialized) {
            pendingLoad = pendingLoad?.copy(second = positionMs)
            return
        }
        guarded(Unit) { mpv.tryCommand("seek", String.format(Locale.ROOT, "%.3f", positionMs / 1000.0), "absolute") }
    }

    override fun setSpeed(speed: Float) {
        if (initialized) guarded(Unit) { mpv.setProperty("speed", speed.toString()) }
    }

    override fun selectSubtitle(id: String?) {
        wantedSubtitle = id
        if (!initialized) return
        val track = id?.let { subtitleTracks[it] }
        guarded(Unit) { if (id == null) mpv.setProperty("sid", "no") else if (track != null) mpv.setProperty("sid", track) }
    }

    override fun selectAudio(id: String?) {
        if (initialized && id != null) guarded(Unit) { mpv.setProperty("aid", id) }
    }

    /** FIT keeps the aspect ratio, FILL crops to cover, STRETCH ignores the aspect ratio: mpv scales, not Compose. */
    fun setScale(scale: VideoScale) {
        if (!initialized) return
        guarded(Unit) {
            mpv.setProperty("panscan", if (scale == VideoScale.FILL) "1.0" else "0.0")
            mpv.setProperty("keepaspect", if (scale == VideoScale.STRETCH) "no" else "yes")
        }
    }

    /** Video brightness 0..1 (0.5 = unchanged), the "brightness" swipe on desktop. */
    fun brightness(): Float = guarded(0.5f) { ((mpv.getProperty("brightness")?.toDoubleOrNull() ?: 0.0) + 100).toFloat() / 200f }

    fun setBrightness(value: Float) {
        if (initialized) guarded(Unit) { mpv.setProperty("brightness", Math.round(value.coerceIn(0f, 1f) * 200 - 100).toString()) }
    }

    /** Player volume 0..1. */
    fun volume(): Float = guarded(1f) { ((mpv.getProperty("volume")?.toDoubleOrNull() ?: 100.0) / 100).toFloat() }

    fun setVolume(value: Float) {
        if (initialized) guarded(Unit) { mpv.setProperty("volume", Math.round(value.coerceIn(0f, 1f) * 100).toString()) }
    }

    /** Writes the current frame (no subtitles, no UI) to [file]. */
    fun screenshot(file: Path): Boolean = initialized && guarded(false) { mpv.tryCommand("screenshot-to-file", file.toString(), "video") }

    override fun release() {
        val running = synchronized(lock) {
            if (closing) return
            closing = true
            initialized
        }
        if (running) {
            gate.read { if (!destroyed) mpv.wakeup() }
        } else {
            X11.withToolkitLock {
                gate.write {
                    destroyed = true
                    X11.trace { "destroying core" }
                    mpv.close()
                    X11.restoreToolkitHandler()
                }
            }
        }
    }

    private fun eventLoop() {
        try {
            while (!closing) {
                val event = mpv.waitEvent(-1.0)
                when (event.id()) {
                    LibMpv.EVENT_SHUTDOWN -> break
                    LibMpv.EVENT_START_FILE -> {
                        loading = true
                        loaded = false
                        error = null
                        eof = false
                    }
                    LibMpv.EVENT_FILE_LOADED -> {
                        loading = false
                        loaded = true
                        addSubtitles()
                    }
                    LibMpv.EVENT_END_FILE -> if (event.endReason() == LibMpv.END_FILE_REASON_ERROR) {
                        loading = false
                        loaded = false
                        error = "mpv error ${event.error()}"
                    }
                    LibMpv.EVENT_PROPERTY_CHANGE -> onProperty(event.property(), event.value())
                    else -> continue
                }
                publish()
            }
        } finally {
            X11.withToolkitLock {
                gate.write {
                    destroyed = true
                    mpv.close()
                }
            }
        }
    }

    private fun onProperty(name: String?, value: String?) {
        when (name) {
            "pause" -> paused = value != "no"
            "eof-reached" -> eof = value == "yes"
            "duration" -> durationMs = value?.toDoubleOrNull()?.let { (it * 1000).toLong() }
            "speed" -> speed = value?.toFloatOrNull() ?: 1f
            "width" -> width = value?.toIntOrNull() ?: 0
            "height" -> height = value?.toIntOrNull() ?: 0
            "sub-text" -> subText = value?.trim()?.ifEmpty { null }
            "sid" -> sid = value?.takeIf { it != "no" && it != "auto" }
            "aid" -> aid = value?.takeIf { it != "no" && it != "auto" }
            "track-list" -> readTracks()
            // mpv's X11 output swaps the process-wide Xlib error handler when it starts and resets it to Xlib's fatal
            // default when it stops: put AWT's (non-fatal) handler back on every transition (see X11).
            "vo-configured" -> X11.trace { "vo-configured=$value" }.also { X11.restoreToolkitHandler() }
        }
    }

    /** External subtitles are added once the file is loaded ("auto": listed but not selected). */
    private fun addSubtitles() {
        val ids = HashMap<String, String>()
        subtitles.forEach { (id, path) -> mpv.tryCommand("sub-add", path, "auto") }
        val count = mpv.getProperty("track-list/count")?.toIntOrNull() ?: 0
        for (i in 0 until count) {
            if (mpv.getProperty("track-list/$i/type") != "sub") continue
            val file = mpv.getProperty("track-list/$i/external-filename") ?: continue
            val trackId = mpv.getProperty("track-list/$i/id") ?: continue
            subtitles.firstOrNull { it.second == file }?.let { ids[it.first] = trackId }
        }
        subtitleTracks = ids
        wantedSubtitle?.let { wanted -> ids[wanted]?.let { mpv.setProperty("sid", it) } }
    }

    private fun readTracks() {
        val count = mpv.getProperty("track-list/count")?.toIntOrNull() ?: 0
        audioTracks = (0 until count).filter { mpv.getProperty("track-list/$it/type") == "audio" }.mapNotNull { i ->
            val id = mpv.getProperty("track-list/$i/id") ?: return@mapNotNull null
            AudioTrack(id, mpv.getProperty("track-list/$i/lang"), mpv.getProperty("track-list/$i/title"))
        }
    }

    private fun publish() {
        val selectedSubtitle = sid?.let { s -> subtitleTracks.entries.firstOrNull { it.value == s }?.key }
        state.value = EngineSnapshot(
            state = when {
                error != null -> EngineState.ERROR
                loading -> EngineState.BUFFERING
                loaded && eof -> EngineState.ENDED
                loaded -> EngineState.READY
                else -> EngineState.IDLE
            },
            playing = loaded && !paused && !eof && error == null,
            durationMs = durationMs,
            speed = speed,
            audioTracks = audioTracks,
            selectedAudio = aid,
            selectedSubtitle = selectedSubtitle,
            videoWidth = width,
            videoHeight = height,
            subtitleText = if (selectedSubtitle != null) subText else null,
            error = error,
        )
    }

    private companion object {
        const val VO_SHUTDOWN_TIMEOUT_NS = 500_000_000L

        val OBSERVED = listOf("pause", "eof-reached", "duration", "speed", "width", "height", "sub-text", "sid", "aid", "vo-configured")

        /** The library hands out file: URIs; mpv wants a plain path (no percent-decoding surprises). */
        fun pathOf(uri: String): String = if (uri.startsWith("file:")) Path.of(URI(uri)).toString() else uri
    }
}
