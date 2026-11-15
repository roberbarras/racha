package io.github.roberbarras.racha.desktop.sync

import io.github.roberbarras.racha.application.sync.SessionCodec
import io.github.roberbarras.racha.application.sync.StoredSession
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The file fallback (no secret service). The libsecret path is never exercised by tests: it is the user's keyring. */
class CredentialStoreTest {
    private val session = StoredSession("https://racha.example.com", "u-1", "d-1", "ana@example.com", "refresh-token")

    @Test
    fun fallbackFileIsPrivateAndRoundTrips() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("racha-cred")
        val file = dir.resolve("session")
        val store = LibSecretCredentialStore(file, useSecretService = false)
        assertNull(store.read())
        store.write(session)
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
        assertEquals(session, store.read())
        store.clear()
        assertNull(store.read())
        dir.toFile().deleteRecursively()
    }

    @Test
    fun codecRejectsGarbage() {
        assertEquals(session, SessionCodec.decode(SessionCodec.encode(session)))
        assertNull(SessionCodec.decode("not json"))
        assertNull(SessionCodec.decode("""{"serverUrl":"x"}"""))
    }
}
