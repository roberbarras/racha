package io.github.roberbarras.racha.android.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.roberbarras.racha.application.sync.StoredSession
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** The sync session is sealed with a Keystore key: it round-trips, is not readable on disk and is gone on sign-out. */
@RunWith(AndroidJUnit4::class)
class KeystoreCredentialStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val session = StoredSession("https://racha.example.com", "u-1", "d-1", "ana@example.com", "refresh-token-secret-value")

    @Test
    fun sealedRoundTripAndClear() = runBlocking<Unit> {
        val store = KeystoreCredentialStore(context)
        store.clear()
        assertNull(store.read())

        store.write(session)
        assertEquals(session, KeystoreCredentialStore(context).read(), "a new instance (process restart) reads it back")
        val onDisk = File(context.noBackupFilesDir, "sync-session.bin").readBytes().decodeToString(throwOnInvalidSequence = false)
        assertFalse("refresh-token-secret-value" in onDisk, "the token is never stored in clear")
        assertFalse("ana@example.com" in onDisk)

        store.clear()
        assertNull(store.read())
    }
}
