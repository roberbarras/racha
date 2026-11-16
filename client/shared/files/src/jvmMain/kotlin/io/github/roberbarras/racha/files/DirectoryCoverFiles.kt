package io.github.roberbarras.racha.files

import io.github.roberbarras.racha.application.library.CoverFiles
import io.github.roberbarras.racha.application.library.GeneratedImage
import io.github.roberbarras.racha.domain.library.ContentId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Generated covers in the app's data folder: one file per course/series, a new name each time so no stale image shows. */
class DirectoryCoverFiles(private val dir: Path) : CoverFiles {
    override suspend fun save(item: ContentId, image: GeneratedImage): String = withContext(Dispatchers.IO) {
        Files.createDirectories(dir)
        val prefix = "cover-${item.value.take(16)}-"
        Files.list(dir).use { files -> files.filter { it.fileName.toString().startsWith(prefix) }.toList() }.forEach(Files::deleteIfExists)
        val target = dir.resolve("$prefix${System.currentTimeMillis()}.${image.extension}")
        val temp = dir.resolve(target.fileName.toString() + ".tmp")
        Files.write(temp, image.bytes)
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        target.toUri().toString()
    }
}
