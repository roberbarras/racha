package io.github.roberbarras.racha.desktop

import io.github.roberbarras.racha.desktop.mpv.LibMpv

/**
 * `racha --self-test`: checks a build without opening a window or playing sound (CI runs it on the AppImage): the
 * video engine loads (the bundled libmpv and its libraries) and decodes a generated test clip. Exit 0 when it works.
 */
object SelfTest {
    fun run(): Int {
        if (!LibMpv.isAvailable()) return fail("libmpv could not be loaded (${System.getenv("RACHA_MPV_LIB") ?: "system"})")
        val mpv = LibMpv()
        try {
            listOf("vo" to "null", "ao" to "null", "config" to "no", "terminal" to "no", "ytdl" to "no").forEach { (k, v) -> mpv.setOption(k, v) }
            mpv.initialize()
            mpv.command("loadfile", "av://lavfi:testsrc=duration=2:size=160x90:rate=10")
            val deadline = System.nanoTime() + 20_000_000_000L
            while (System.nanoTime() < deadline) {
                val event = mpv.waitEvent(1.0)
                when (event.id()) {
                    LibMpv.EVENT_FILE_LOADED -> {
                        val width = mpv.getProperty("width")
                        println("SELF-TEST ok: mpv ${mpv.getProperty("mpv-version")}, ffmpeg ${mpv.getProperty("ffmpeg-version")}, decoded ${width}x${mpv.getProperty("height")}")
                        return if (width == "160") 0 else fail("unexpected video width $width")
                    }
                    LibMpv.EVENT_END_FILE -> return fail("the test clip did not load (error ${event.error()})")
                }
            }
            return fail("timed out")
        } finally {
            mpv.close()
        }
    }

    private fun fail(reason: String): Int {
        System.err.println("SELF-TEST failed: $reason")
        return 1
    }
}
