package io.github.roberbarras.racha.spike.video

import java.io.File
import java.util.Locale

/**
 * Headless libmpv: metadata (§3.3) and a thumbnail at ~10 % of the video (ADR-0008), timed per step.
 * Usage: `probe <video> <outDir>`.
 */
fun runProbe(video: String, outDir: String) {
    val t0 = System.nanoTime()
    val metadata = Mpv().use { mpv ->
        mpv.setOption("vo", "null")
        mpv.setOption("ao", "null")
        mpv.setOption("pause", "yes")
        mpv.setOption("terminal", "no")
        mpv.initialize()
        mpv.command("loadfile", video)
        awaitProperty(mpv, "duration")
        val tracks = (0 until (mpv.getProperty("track-list/count")?.toInt() ?: 0)).map { i ->
            listOf("type", "codec", "lang", "demux-w", "demux-h", "demux-fps", "demux-channel-count")
                .mapNotNull { key -> mpv.getProperty("track-list/$i/$key")?.let { "$key=$it" } }
                .joinToString(",")
        }
        mapOf(
            "duration" to mpv.getProperty("duration"),
            "fileFormat" to mpv.getProperty("file-format"),
            "width" to mpv.getProperty("width"),
            "height" to mpv.getProperty("height"),
            "fps" to mpv.getProperty("container-fps"),
            "fileSize" to mpv.getProperty("file-size"),
            "tracks" to tracks.joinToString(" | "),
        )
    }
    val t1 = System.nanoTime()

    File(outDir).deleteRecursively()
    File(outDir).mkdirs()
    Mpv().use { mpv ->
        mpv.setOption("vo", "image")
        mpv.setOption("vo-image-format", "webp")
        mpv.setOption("vo-image-webp-quality", "80")
        mpv.setOption("vo-image-outdir", outDir)
        mpv.setOption("ao", "null")
        mpv.setOption("audio", "no")
        mpv.setOption("hwdec", "no") // one frame: avoiding hw decoder setup is faster
        mpv.setOption("start", "10%")
        mpv.setOption("frames", "1")
        mpv.setOption("vf", "scale=480:-2")
        mpv.setOption("terminal", "no")
        mpv.initialize()
        mpv.command("loadfile", video)
        awaitProperty(mpv, "idle-active") { it == "yes" && !File(outDir).list().isNullOrEmpty() }
    }
    val t2 = System.nanoTime()
    val thumbnail = File(outDir).listFiles()?.maxByOrNull { it.lastModified() }

    println("PROBE metadata=$metadata")
    println(
        "PROBE metadataMs=%.0f thumbnailMs=%.0f thumbnail=%s (%d bytes)".format(
            Locale.ROOT, (t1 - t0) / 1e6, (t2 - t1) / 1e6, thumbnail?.name, thumbnail?.length() ?: 0,
        ),
    )
}

private fun awaitProperty(mpv: Mpv, name: String, accept: (String) -> Boolean = { true }) {
    val deadline = System.nanoTime() + 10_000_000_000L
    while (System.nanoTime() < deadline) {
        val value = mpv.getProperty(name)
        if (value != null && accept(value)) return
        Thread.sleep(5)
    }
    error("timed out waiting for $name")
}
