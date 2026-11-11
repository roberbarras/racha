package io.github.roberbarras.racha.spike.video

import java.io.File
import java.util.Locale

/**
 * Process CPU time (all threads: JVM, Compose, mpv decoder/render), peak RSS, optional package energy (RAPL,
 * includes the integrated GPU) and mpv playback counters over the measured window.
 */
class Measurement(private val config: Config, private val stats: Stats, private val mpv: Mpv?) {
    private var startWallNanos = 0L
    private var startCpuTicks = 0L
    private var startEnergyMicroJoules: Long? = null
    private var startVideoFrames = 0L
    private var startComposeDraws = 0L
    private var startDropped = 0L

    fun start() {
        startWallNanos = System.nanoTime()
        startCpuTicks = cpuTicks()
        startEnergyMicroJoules = energyMicroJoules()
        startVideoFrames = stats.videoFrames.get()
        startComposeDraws = stats.composeDraws.get()
        startDropped = mpv?.getProperty("frame-drop-count")?.toLongOrNull() ?: 0
    }

    fun finish(): String {
        val seconds = (System.nanoTime() - startWallNanos) / 1e9
        val cpuPercentOfOneCore = (cpuTicks() - startCpuTicks) / CLOCK_TICKS_PER_SECOND / seconds * 100
        val watts = startEnergyMicroJoules?.let { start -> energyMicroJoules()?.let { (it - start) / 1e6 / seconds } }
        val fields = linkedMapOf<String, Any?>(
            "mode" to config.mode,
            "size" to "${config.width}x${config.height}",
            "seconds" to "%.1f".format(Locale.ROOT, seconds),
            "cpuPercentOneCore" to "%.1f".format(Locale.ROOT, cpuPercentOfOneCore),
            "packageWatts" to watts?.let { "%.2f".format(Locale.ROOT, it) },
            "rssMiB" to rssKiB() / 1024,
            "videoFps" to "%.1f".format(Locale.ROOT, (stats.videoFrames.get() - startVideoFrames) / seconds),
            "composeDrawsPerSecond" to "%.1f".format(Locale.ROOT, (stats.composeDraws.get() - startComposeDraws) / seconds),
            "droppedFrames" to mpv?.getProperty("frame-drop-count")?.toLongOrNull()?.minus(startDropped),
            "hwdecRequested" to config.hwdec,
            "hwdec" to mpv?.getProperty("hwdec-current"),
            "videoCodec" to mpv?.getProperty("video-codec"),
            "estimatedVfFps" to mpv?.getProperty("estimated-vf-fps"),
        )
        return fields.entries.joinToString(prefix = "{", postfix = "}") { (k, v) ->
            "\"$k\":" + if (v is Number || v == null) "$v" else "\"$v\""
        }
    }

    private fun cpuTicks(): Long {
        // /proc/self/stat: fields after the ")" of the command name; utime and stime are fields 14 and 15.
        val stat = File("/proc/self/stat").readText()
        val fields = stat.substringAfterLast(") ").split(' ')
        return fields[11].toLong() + fields[12].toLong()
    }

    private fun rssKiB(): Long = File("/proc/self/status").readLines()
        .first { it.startsWith("VmHWM:") }.filter(Char::isDigit).toLong()

    private fun energyMicroJoules(): Long? = runCatching { File(RAPL_PACKAGE).readText().trim().toLong() }.getOrNull()

    private companion object {
        const val CLOCK_TICKS_PER_SECOND = 100.0
        const val RAPL_PACKAGE = "/sys/class/powercap/intel-rapl:0/energy_uj"
    }
}
