package io.github.roberbarras.racha.application.remote

import io.github.roberbarras.racha.domain.library.RelativePath
import io.github.roberbarras.racha.domain.remote.LocalCopy
import io.github.roberbarras.racha.domain.remote.RemoteFile
import io.github.roberbarras.racha.domain.remote.RemoteProvider
import io.github.roberbarras.racha.domain.remote.RemoteSource
import kotlinx.coroutines.flow.Flow

/** How to reach an account; kept only in the keyring ([encode]/[decode] as one secret). */
sealed interface RemoteCredentials {
    /** Nextcloud and other WebDAV servers: an app password, never the account password (Nextcloud login flow). */
    data class WebDav(val user: String, val password: String) : RemoteCredentials

    /** Google Drive: the refresh token of the user's consent (read-only access). */
    data class Drive(val refreshToken: String) : RemoteCredentials

    companion object {
        fun encode(credentials: RemoteCredentials): String = when (credentials) {
            is WebDav -> "webdav\n${credentials.user}\n${credentials.password}"
            is Drive -> "drive\n${credentials.refreshToken}"
        }

        fun decode(text: String?): RemoteCredentials? {
            val lines = text?.lines() ?: return null
            return when (lines.firstOrNull()) {
                "webdav" -> if (lines.size >= 3) WebDav(lines[1], lines.drop(2).joinToString("\n")) else null
                "drive" -> lines.getOrNull(1)?.let(::Drive)
                else -> null
            }
        }
    }
}

/** A folder seen while choosing what to add: [address] is what the provider needs (URL or Drive id). */
data class RemoteDirectory(val name: String, val address: String, val path: String)

/** One provider (WebDAV, Google Drive). Network failures are [io.github.roberbarras.racha.application.sync.RemoteError]. */
interface RemoteStorage {
    val provider: RemoteProvider

    /** The folders inside [address] (null: the top of the account), to pick one. */
    suspend fun folders(credentials: RemoteCredentials, base: String, address: String?): List<RemoteDirectory>

    /** Every file under the folder at [address], with paths relative to it. */
    suspend fun files(credentials: RemoteCredentials, address: String): List<RemoteFile>

    /**
     * Downloads [file] next to its final place (`target` + [io.github.roberbarras.racha.domain.remote.RemoteCourses.PART]),
     * resuming what an earlier attempt left, and renames it once complete with the remote modification time.
     * [target] is a `file://` URI. [progress] receives the bytes of this file written so far.
     */
    suspend fun download(credentials: RemoteCredentials, file: RemoteFile, target: String, progress: (Long) -> Unit)
}

/** The downloaded copies, in the destination folder. */
interface LocalCopies {
    /** Creates the folder if needed; false if it cannot be written. */
    suspend fun prepare(destination: String): Boolean

    suspend fun list(destination: String): Map<RelativePath, LocalCopy>

    /** The `file://` URI of [path] inside [destination]. */
    fun target(destination: String, path: RelativePath): String

    suspend fun delete(destination: String, paths: Collection<RelativePath>)

    suspend fun deleteAll(destination: String)

    /** Free space where [destination] is, in bytes (null if unknown). */
    suspend fun freeSpace(destination: String): Long?
}

interface RemoteSourceRepository {
    fun observe(): Flow<List<RemoteSource>>

    suspend fun all(): List<RemoteSource>

    suspend fun get(id: String): RemoteSource?

    suspend fun save(source: RemoteSource)

    suspend fun delete(id: String)
}
