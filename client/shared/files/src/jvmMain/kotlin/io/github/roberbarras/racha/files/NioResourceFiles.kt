package io.github.roberbarras.racha.files

import io.github.roberbarras.racha.application.resource.ResourceFiles
import io.github.roberbarras.racha.application.resource.ResourceTextFile
import io.github.roberbarras.racha.domain.library.RelativePath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path

/** [ResourceFiles] on java.nio. Text is UTF-8 (with or without BOM) or, failing that, Windows-1252 (old .txt/.url). */
class NioResourceFiles : ResourceFiles {
    override suspend fun readText(location: String, path: RelativePath, maxBytes: Int): ResourceTextFile? = withContext(Dispatchers.IO) {
        val file = resolve(location, path)?.takeIf(Files::isRegularFile) ?: return@withContext null
        runCatching {
            val bytes = Files.newInputStream(file).use { it.readNBytes(maxBytes + 1) }
            val truncated = bytes.size > maxBytes
            ResourceTextFile(decode(if (truncated) bytes.copyOf(maxBytes) else bytes, truncated), truncated)
        }.getOrNull()
    }

    override fun localPath(location: String, path: RelativePath): String? = resolve(location, path)?.takeIf(Files::exists)?.toString()

    /** Inside the root only: a path that normalises outside it is refused. */
    private fun resolve(location: String, path: RelativePath): Path? {
        val root = NioLibrarySource.pathOf(location).toAbsolutePath().normalize()
        return root.resolve(path.value).normalize().takeIf { it.startsWith(root) }
    }

    private fun decode(bytes: ByteArray, truncated: Boolean): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        // A cut file may end inside a multi-byte character: drop that partial character before checking UTF-8.
        val body = if (truncated) bytes.copyOf(bytes.size - partialUtf8Tail(bytes)) else bytes
        return runCatching { strictUtf8().decode(ByteBuffer.wrap(body)).toString() }.getOrNull()
            ?: String(bytes, Charset.forName("windows-1252"))
    }

    private fun strictUtf8() = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)

    /** Bytes at the end that start a UTF-8 sequence without all its continuation bytes. */
    private fun partialUtf8Tail(bytes: ByteArray): Int {
        for (back in 1..minOf(3, bytes.size)) {
            val b = bytes[bytes.size - back].toInt() and 0xFF
            if (b and 0xC0 == 0x80) continue // continuation byte: keep looking for the lead byte
            val length = when {
                b and 0xE0 == 0xC0 -> 2
                b and 0xF0 == 0xE0 -> 3
                b and 0xF8 == 0xF0 -> 4
                else -> 1
            }
            return if (length > back) back else 0
        }
        return 0
    }
}
