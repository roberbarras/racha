package io.github.roberbarras.racha.files

import io.github.roberbarras.racha.application.library.ThumbnailCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

/** The poster cache folder of a platform (desktop: `~/.cache/racha/thumbs`, Android: the app's cache). */
class DirectoryThumbnailCache(private val dir: Path) : ThumbnailCache {
    override val locationPrefix: String = dir.toUri().toString()

    override suspend fun clear(): Long = withContext(Dispatchers.IO) {
        if (!Files.isDirectory(dir)) return@withContext 0L
        Files.list(dir).use { files ->
            files.filter(Files::isRegularFile).toList().sumOf { file ->
                val size = runCatching { Files.size(file) }.getOrDefault(0L)
                if (runCatching { Files.deleteIfExists(file) }.getOrDefault(false)) size else 0L
            }
        }
    }
}
