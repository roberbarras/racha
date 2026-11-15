package io.github.roberbarras.racha.desktop.sync

import io.github.roberbarras.racha.application.sync.CredentialStore
import io.github.roberbarras.racha.application.sync.SessionCodec
import io.github.roberbarras.racha.application.sync.StoredSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.invoke.MethodHandle
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * The sync session in the desktop's secret service (KWallet, GNOME Keyring…) through libsecret, called with FFM.
 * Without libsecret or a secret service (minimal systems), a file readable only by the user is the fallback.
 */
class LibSecretCredentialStore(private val fallback: Path, useSecretService: Boolean = true) : CredentialStore {
    /** Tests pass false: they must never touch the user's real keyring. */
    private val secret: LibSecret? = if (useSecretService) runCatching { LibSecret() }.getOrNull() else null

    override suspend fun read(): StoredSession? = withContext(Dispatchers.IO) {
        secret?.runCatching { lookup() }?.getOrNull()?.let(SessionCodec::decode)
            ?: runCatching { Files.readString(fallback) }.getOrNull()?.let(SessionCodec::decode)
    }

    override suspend fun write(session: StoredSession): Unit = withContext(Dispatchers.IO) {
        val text = SessionCodec.encode(session)
        if (secret?.runCatching { store(text) }?.getOrDefault(false) == true) {
            Files.deleteIfExists(fallback)
        } else {
            writePrivate(text)
        }
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        secret?.runCatching { clear() }
        Files.deleteIfExists(fallback)
        Unit
    }

    private fun writePrivate(text: String) {
        Files.createDirectories(fallback.parent)
        val temp = fallback.resolveSibling(fallback.fileName.toString() + ".tmp")
        Files.deleteIfExists(temp)
        Files.createFile(temp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        Files.writeString(temp, text)
        Files.move(temp, fallback, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    /** `secret_password_{store,lookup,clear}_sync` with a one-attribute schema (`app` = racha). */
    private class LibSecret {
        private val linker = Linker.nativeLinker()
        private val lib = SymbolLookup.libraryLookup("libsecret-1.so.0", Arena.global())
        private val schema: MemorySegment = Arena.global().allocate(SCHEMA_BYTES).also { s ->
            s.set(ADDRESS, 0, Arena.global().allocateFrom("io.github.roberbarras.racha.Session"))
            s.set(JAVA_INT, 8, 0) // SECRET_SCHEMA_NONE
            s.set(ADDRESS, 16, Arena.global().allocateFrom(ATTRIBUTE)) // attributes[0].name
            s.set(JAVA_INT, 24, 0) // SECRET_SCHEMA_ATTRIBUTE_STRING; attributes[1] stays zero: end of list
        }
        // Fixed arguments, then (name, value, NULL) as C varargs.
        private val store = fn("secret_password_store_sync", 6, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS))
        private val lookup = fn("secret_password_lookup_sync", 3, FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS))
        private val clear = fn("secret_password_clear_sync", 3, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS))
        private val free = linker.downcallHandle(lib.find("secret_password_free").orElseThrow(), FunctionDescriptor.ofVoid(ADDRESS))

        fun store(value: String): Boolean = Arena.ofConfined().use { a ->
            val ok = store.invokeWithArguments(schema, MemorySegment.NULL, a.allocateFrom("Racha sync session"), a.allocateFrom(value),
                MemorySegment.NULL, MemorySegment.NULL, a.allocateFrom(ATTRIBUTE), a.allocateFrom(APP), MemorySegment.NULL) as Int
            ok != 0
        }

        fun lookup(): String? = Arena.ofConfined().use { a ->
            val result = lookup.invokeWithArguments(schema, MemorySegment.NULL, MemorySegment.NULL,
                a.allocateFrom(ATTRIBUTE), a.allocateFrom(APP), MemorySegment.NULL) as MemorySegment
            if (result == MemorySegment.NULL) return null
            try {
                result.reinterpret(Long.MAX_VALUE).getString(0)
            } finally {
                free.invokeWithArguments(result)
            }
        }

        fun clear() {
            Arena.ofConfined().use { a ->
                clear.invokeWithArguments(schema, MemorySegment.NULL, MemorySegment.NULL, a.allocateFrom(ATTRIBUTE), a.allocateFrom(APP), MemorySegment.NULL)
            }
        }

        private fun fn(name: String, fixedArgs: Int, descriptor: FunctionDescriptor): MethodHandle =
            linker.downcallHandle(lib.find(name).orElseThrow(), descriptor, Linker.Option.firstVariadicArg(fixedArgs))

        private companion object {
            const val ATTRIBUTE = "app"
            const val APP = "racha"
            // SecretSchema: name, flags, attributes[32] {name, type}, reserved int, 7 reserved pointers.
            const val SCHEMA_BYTES = 8L + 8 + 32 * 16 + 8 + 7 * 8
        }
    }
}
