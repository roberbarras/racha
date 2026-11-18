package io.github.roberbarras.racha.files

import io.github.roberbarras.racha.application.remote.LocalCopies
import io.github.roberbarras.racha.domain.library.RelativePath
import io.github.roberbarras.racha.domain.remote.LocalCopy
import io.github.roberbarras.racha.domain.remote.RemoteCourses
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.deleteIfExists
import kotlin.time.Instant

/** The downloaded copies of remote folders (H19), on the device's file system. */
class NioLocalCopies : LocalCopies {
    private fun root(destination: String): Path = Path.of(URI(destination))

    override suspend fun prepare(destination: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { Files.createDirectories(root(destination)); Files.isWritable(root(destination)) }.getOrDefault(false)
    }

    override suspend fun list(destination: String): Map<RelativePath, LocalCopy> = withContext(Dispatchers.IO) {
        val root = root(destination)
        if (!Files.isDirectory(root)) return@withContext emptyMap()
        val out = HashMap<RelativePath, LocalCopy>()
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val relative = root.relativize(file).joinToString("/")
                if (attrs.isRegularFile && !relative.endsWith(RemoteCourses.PART)) {
                    runCatching { RelativePath.of(relative) }.getOrNull()?.let {
                        out[it] = LocalCopy(attrs.size(), Instant.fromEpochMilliseconds(attrs.lastModifiedTime().toMillis()))
                    }
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: java.io.IOException) = FileVisitResult.CONTINUE
        })
        out
    }

    override fun target(destination: String, path: RelativePath): String = root(destination).resolve(path.value).normalize().also {
        // A remote name like "../x" must never write outside the destination.
        require(it.startsWith(root(destination).normalize())) { "outside the destination: ${path.value}" }
    }.toUri().toString()

    override suspend fun delete(destination: String, paths: Collection<RelativePath>) = withContext(Dispatchers.IO) {
        val root = root(destination).normalize()
        paths.forEach { path ->
            val file = root.resolve(path.value).normalize()
            if (file.startsWith(root)) {
                file.deleteIfExists()
                // Folders left empty go too, up to the destination.
                var parent = file.parent
                while (parent != null && parent != root && parent.startsWith(root) && Files.isDirectory(parent) && Files.list(parent).use { !it.findAny().isPresent }) {
                    Files.delete(parent)
                    parent = parent.parent
                }
            }
        }
    }

    override suspend fun deleteAll(destination: String) = withContext(Dispatchers.IO) {
        val root = root(destination)
        if (!Files.isDirectory(root)) return@withContext
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult { Files.deleteIfExists(file); return FileVisitResult.CONTINUE }
            override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult { Files.deleteIfExists(dir); return FileVisitResult.CONTINUE }
        })
    }

    override suspend fun freeSpace(destination: String): Long? = withContext(Dispatchers.IO) {
        var path: Path? = root(destination)
        while (path != null && !Files.exists(path)) path = path.parent
        path?.let { runCatching { Files.getFileStore(it).usableSpace }.getOrNull() }
    }
}
