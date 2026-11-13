package io.github.roberbarras.racha.files

import io.github.roberbarras.racha.application.library.scan.LibrarySource
import io.github.roberbarras.racha.domain.library.RelativePath
import io.github.roberbarras.racha.domain.library.scan.FileEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URI
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.EnumSet
import kotlin.coroutines.CoroutineContext
import kotlin.time.Instant

/**
 * [LibrarySource] over `file://` roots with java.nio. Symbolic links are followed (loops are detected and skipped),
 * unreadable entries are ignored, excluded directories are never entered. Modification times are truncated to
 * milliseconds, the precision stored in the database, so unchanged files compare equal on the next scan.
 */
class NioLibrarySource(private val io: CoroutineContext = Dispatchers.IO) : LibrarySource {

    override fun displayName(location: String): String = pathOf(location).fileName?.toString() ?: location

    override suspend fun list(location: String, descend: (RelativePath) -> Boolean): List<FileEntry> = withContext(io) {
        val root = pathOf(location)
        if (!Files.isDirectory(root)) return@withContext emptyList()
        val entries = mutableListOf<FileEntry>()
        Files.walkFileTree(
            root,
            EnumSet.of(FileVisitOption.FOLLOW_LINKS),
            Int.MAX_VALUE,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (dir == root) return FileVisitResult.CONTINUE
                    val path = relative(root, dir) ?: return FileVisitResult.SKIP_SUBTREE
                    if (!descend(path)) return FileVisitResult.SKIP_SUBTREE
                    entries += FileEntry(path, isDirectory = true, size = 0, modifiedAt = instant(attrs))
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile) relative(root, file)?.let { entries += FileEntry(it, false, attrs.size(), instant(attrs)) }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
            },
        )
        entries
    }

    override fun uri(location: String, path: RelativePath): String = pathOf(location).resolve(path.value).toUri().toString()

    private fun relative(root: Path, path: Path): RelativePath? =
        runCatching { RelativePath.of(root.relativize(path).joinToString("/")) }.getOrNull()

    private fun instant(attrs: BasicFileAttributes) = Instant.fromEpochMilliseconds(attrs.lastModifiedTime().toMillis())

    companion object {
        fun pathOf(location: String): Path = if (location.startsWith("file:")) Path.of(URI(location)) else Path.of(location)

        fun locationOf(path: Path): String = path.toAbsolutePath().normalize().toUri().toString()
    }
}
