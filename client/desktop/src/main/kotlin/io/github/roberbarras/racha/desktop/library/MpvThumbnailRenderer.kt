package io.github.roberbarras.racha.desktop.library

import io.github.roberbarras.racha.application.library.scan.ThumbnailRenderer
import io.github.roberbarras.racha.desktop.mpv.LibMpv
import io.github.roberbarras.racha.domain.library.RelativePath
import io.github.roberbarras.racha.files.NioLibrarySource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale
import kotlin.io.path.deleteIfExists
import kotlin.io.path.listDirectoryEntries

/**
 * Writes one 480 px-wide WebP frame per video into [cacheDir] with libmpv's image output (software decoding: one
 * frame does not justify setting up a hardware decoder). Cached by key, so each frame is rendered once.
 */
class MpvThumbnailRenderer(private val cacheDir: Path) : ThumbnailRenderer {

    override suspend fun render(location: String, path: RelativePath, durationSec: Long?, fraction: Double, cacheKey: String): String? =
        withContext(Dispatchers.IO) {
            val target = cacheDir.resolve("$cacheKey.webp")
            if (Files.exists(target)) return@withContext target.toUri().toString()
            Files.createDirectories(cacheDir)
            val work = Files.createTempDirectory(cacheDir, "render-")
            try {
                LibMpv().use { mpv ->
                    mpv.setOption("vo", "image")
                    mpv.setOption("vo-image-format", "webp")
                    mpv.setOption("vo-image-webp-quality", "80")
                    mpv.setOption("vo-image-outdir", work.toString())
                    mpv.setOption("audio", "no")
                    mpv.setOption("hwdec", "no")
                    mpv.setOption("start", String.format(Locale.ROOT, "%.1f%%", fraction * 100))
                    mpv.setOption("frames", "1")
                    mpv.setOption("vf", "scale=480:-2")
                    mpv.setOption("terminal", "no")
                    mpv.setOption("config", "no")
                    mpv.initialize()
                    mpv.command("loadfile", NioLibrarySource.pathOf(location).resolve(path.value).toString())
                    val deadline = System.nanoTime() + 15_000_000_000L
                    while (System.nanoTime() < deadline) {
                        val event = mpv.waitEvent(1.0)
                        if (event.id == LibMpv.EVENT_END_FILE || event.id == LibMpv.EVENT_SHUTDOWN) break
                    }
                }
                val frame = work.listDirectoryEntries("*.webp").firstOrNull() ?: return@withContext null
                Files.move(frame, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                target.toUri().toString()
            } finally {
                work.listDirectoryEntries().forEach { it.deleteIfExists() }
                work.deleteIfExists()
            }
        }
}
