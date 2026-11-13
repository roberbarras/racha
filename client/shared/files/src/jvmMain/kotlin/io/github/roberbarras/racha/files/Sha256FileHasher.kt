package io.github.roberbarras.racha.files

import io.github.roberbarras.racha.application.library.scan.FileHasher
import io.github.roberbarras.racha.domain.library.RelativePath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/** Streams the file through SHA-256 with a 1 MiB direct buffer; cancellable between chunks. */
class Sha256FileHasher(private val io: CoroutineContext = Dispatchers.IO) : FileHasher {

    override suspend fun sha256(location: String, path: RelativePath): String = withContext(io) {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteBuffer.allocateDirect(1 shl 20)
        FileChannel.open(NioLibrarySource.pathOf(location).resolve(path.value), StandardOpenOption.READ).use { channel ->
            while (channel.read(buffer) >= 0) {
                coroutineContext.ensureActive()
                buffer.flip()
                digest.update(buffer)
                buffer.clear()
            }
        }
        digest.digest().toHexString()
    }
}
