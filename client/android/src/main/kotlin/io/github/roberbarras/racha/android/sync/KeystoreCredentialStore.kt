package io.github.roberbarras.racha.android.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.roberbarras.racha.application.sync.CredentialStore
import io.github.roberbarras.racha.application.sync.SessionCodec
import io.github.roberbarras.racha.application.sync.StoredSession
import io.github.roberbarras.racha.application.shared.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The sync session, sealed by [KeystoreSecretStore] in the file it has always used. */
class KeystoreCredentialStore(context: Context) : CredentialStore {
    private val store = KeystoreSecretStore(context, "sync-session.bin")

    override suspend fun read(): StoredSession? = store.read()?.let(SessionCodec::decode)

    override suspend fun write(session: StoredSession) = store.write(SessionCodec.encode(session))

    override suspend fun clear() = store.clear()
}

/**
 * One secret encrypted with an AES-GCM key that lives in the Android Keystore (never leaves it, hardware backed where
 * available). The ciphertext sits in the no-backup folder: a backup restored elsewhere has no key.
 */
class KeystoreSecretStore(context: Context, fileName: String) : SecretStore {
    private val file = File(context.noBackupFilesDir, fileName)

    override suspend fun read(): String? = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext null
        runCatching {
            val bytes = file.readBytes()
            val iv = bytes.copyOfRange(0, IV_BYTES)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv)) }
            cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES).decodeToString()
        }.getOrNull()
    }

    override suspend fun write(value: String) = withContext(Dispatchers.IO) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.iv + cipher.doFinal(value.encodeToByteArray())
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeBytes(sealed)
        temp.renameTo(file)
        Unit
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        file.delete()
        Unit
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "racha-sync-session"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
