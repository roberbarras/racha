package io.github.roberbarras.racha.files

import io.github.roberbarras.racha.domain.library.RelativePath
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class NioAdaptersTest {
    private val root: Path = Files.createTempDirectory("racha-nio").resolve("Mi Curso")
    private val source = NioLibrarySource()

    private fun write(path: String, text: String = path): Path =
        root.resolve(path).also { Files.createDirectories(it.parent); Files.writeString(it, text) }

    @Test
    fun listsFilesAndDirectoriesRelativeToTheRootSkippingExcludedSubtrees() = runBlocking {
        write("01 - Introduction/001 Course Introduction.mp4")
        write("01 - Introduction/001 Course Introduction_es.srt")
        write("temp/index.m3u8")
        val location = NioLibrarySource.locationOf(root)

        val entries = source.list(location) { it.name != "temp" }

        assertEquals(
            setOf("01 - Introduction" to true, "01 - Introduction/001 Course Introduction.mp4" to false, "01 - Introduction/001 Course Introduction_es.srt" to false),
            entries.map { it.path.value to it.isDirectory }.toSet(),
        )
        assertEquals("Mi Curso", source.displayName(location))
        assertTrue(source.uri(location, RelativePath.of("01 - Introduction/001 Course Introduction.mp4")).startsWith("file:///"))
    }

    @Test
    fun modificationTimesAreStoredAtMillisecondPrecision() = runBlocking {
        val file = write("a.mp4")
        Files.setLastModifiedTime(file, FileTime.from(java.time.Instant.parse("2026-11-13T09:00:00.123456789Z")))

        val entry = source.list(NioLibrarySource.locationOf(root)) { true }.single()

        assertEquals(Instant.parse("2026-11-13T09:00:00.123Z"), entry.modifiedAt)
        assertEquals(5, entry.size)
    }

    @Test
    fun missingRootYieldsNothing() = runBlocking {
        assertEquals(emptyList(), source.list(NioLibrarySource.locationOf(root.resolve("unplugged-disk"))) { true })
    }

    @Test
    fun hashesLargeFilesInChunks() = runBlocking {
        val bytes = ByteArray(3 * (1 shl 20) + 7) { (it % 251).toByte() }
        val file = root.resolve("big.mkv").also { Files.createDirectories(it.parent); Files.write(it, bytes) }

        val hash = Sha256FileHasher().sha256(NioLibrarySource.locationOf(root), RelativePath.of(file.fileName.toString()))

        assertEquals(MessageDigest.getInstance("SHA-256").digest(bytes).toHexString(), hash)
    }
}
