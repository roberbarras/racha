package io.github.roberbarras.racha.desktop.library

import io.github.roberbarras.racha.application.library.scan.MediaProbe
import io.github.roberbarras.racha.desktop.mpv.LibMpv
import io.github.roberbarras.racha.domain.library.MediaInfo
import io.github.roberbarras.racha.domain.library.RelativePath
import io.github.roberbarras.racha.files.NioLibrarySource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.roundToLong

/**
 * Metadata through one long-lived headless libmpv core (no video/audio output, no decoding beyond demuxing), reused
 * for every file: creating a core per file costs ~300 ms, reuse brings it close to the demuxer time (ADR-0008).
 */
class MpvMediaProbe : MediaProbe, AutoCloseable {
    private val mutex = Mutex()
    private var mpv: LibMpv? = null

    override suspend fun probe(location: String, path: RelativePath): MediaInfo? = mutex.withLock {
        withContext(Dispatchers.IO) {
            val core = mpv ?: newCore().also { mpv = it }
            val file = NioLibrarySource.pathOf(location).resolve(path.value).toString()
            core.command("loadfile", file)
            val loaded = awaitLoad(core)
            val info = if (loaded) read(core) else null
            core.command("stop")
            awaitIdle(core)
            info
        }
    }

    private fun newCore() = LibMpv().apply {
        setOption("vo", "null")
        setOption("ao", "null")
        setOption("audio", "no")
        setOption("pause", "yes")
        setOption("idle", "yes")
        setOption("hwdec", "no")
        setOption("terminal", "no")
        setOption("config", "no") // ignore the user's mpv.conf
        setOption("load-scripts", "no")
        initialize()
    }

    private fun awaitLoad(core: LibMpv): Boolean {
        val deadline = System.nanoTime() + TIMEOUT_NANOS
        while (System.nanoTime() < deadline) {
            val event = core.waitEvent(1.0)
            when (event.id) {
                LibMpv.EVENT_FILE_LOADED -> return true
                LibMpv.EVENT_END_FILE -> if (event.endReason == LibMpv.END_FILE_REASON_ERROR) return false
                LibMpv.EVENT_SHUTDOWN -> return false
            }
        }
        return false
    }

    private fun awaitIdle(core: LibMpv) {
        val deadline = System.nanoTime() + TIMEOUT_NANOS
        while (System.nanoTime() < deadline && core.getProperty("idle-active") != "yes") core.waitEvent(0.2)
    }

    private fun read(core: LibMpv): MediaInfo {
        val duration = core.getProperty("duration")?.toDoubleOrNull()
        val video = (0 until (core.getProperty("track-list/count")?.toIntOrNull() ?: 0))
            .firstOrNull { core.getProperty("track-list/$it/type") == "video" && core.getProperty("track-list/$it/albumart") != "yes" }
        fun track(key: String) = video?.let { core.getProperty("track-list/$it/$key") }
        val fileSize = core.getProperty("file-size")?.toLongOrNull()
        return MediaInfo(
            durationSec = duration?.roundToLong(),
            width = track("demux-w")?.toIntOrNull(),
            height = track("demux-h")?.toIntOrNull(),
            videoCodec = track("codec"),
            fps = track("demux-fps")?.toDoubleOrNull(),
            // Average over the whole file (container + audio): the per-stream bitrate is only known while playing.
            bitrateKbps = if (fileSize != null && duration != null && duration > 0) (fileSize * 8 / duration / 1000).toInt() else null,
        )
    }

    override fun close() {
        mpv?.close()
        mpv = null
    }

    private companion object {
        const val TIMEOUT_NANOS = 15_000_000_000L
    }
}
