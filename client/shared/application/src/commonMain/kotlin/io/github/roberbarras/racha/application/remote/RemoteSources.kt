package io.github.roberbarras.racha.application.remote

import io.github.roberbarras.racha.application.browse.ManageRoots
import io.github.roberbarras.racha.application.library.CategoryRepository
import io.github.roberbarras.racha.application.library.scan.ScanLibrary
import io.github.roberbarras.racha.application.shared.AppLog
import io.github.roberbarras.racha.application.shared.Clock
import io.github.roberbarras.racha.application.shared.SecretStore
import io.github.roberbarras.racha.application.sync.RemoteError
import io.github.roberbarras.racha.application.sync.reportCode
import io.github.roberbarras.racha.domain.library.CategoryKey
import io.github.roberbarras.racha.domain.library.RootLayout
import io.github.roberbarras.racha.domain.library.scan.ExclusionRules
import io.github.roberbarras.racha.domain.remote.DownloadMode
import io.github.roberbarras.racha.domain.remote.RemoteCourse
import io.github.roberbarras.racha.domain.remote.RemoteCourses
import io.github.roberbarras.racha.domain.remote.RemoteProvider
import io.github.roberbarras.racha.domain.remote.RemoteSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** What adding a remote folder needs (the rest is decided here). */
data class NewRemoteSource(
    val provider: RemoteProvider,
    val name: String,
    val account: String,
    /** WebDAV: the account's base URL; Drive: unused. Kept to browse again. */
    val address: String,
    val location: String,
    val category: CategoryKey,
    val layout: RootLayout,
    val destination: String,
    val mode: DownloadMode,
    val selected: Set<String>,
    val wifiOnly: Boolean,
)

/** A remote folder's download, as the UI shows it. */
sealed interface DownloadState {
    data object Idle : DownloadState
    data object Listing : DownloadState
    data class Downloading(val file: String, val files: Int, val doneFiles: Int, val bytes: Long, val doneBytes: Long) : DownloadState {
        val fraction: Float get() = if (bytes <= 0) 0f else (doneBytes.toFloat() / bytes).coerceIn(0f, 1f)
    }
    /** [reason]: `space`, `credentials`, `unreachable`, `destination`, `rejected:<status>`… */
    data class Failed(val reason: String) : DownloadState
}

/**
 * Remote folders (H19): Nextcloud/WebDAV or Google Drive folders whose courses are downloaded to this device, all of
 * them or the ones the user picks. The destination is an ordinary folder of the library, so downloaded courses are
 * scanned and played like any other; a course appears as soon as its files are here.
 */
@OptIn(ExperimentalUuidApi::class)
class RemoteSources(
    private val repository: RemoteSourceRepository,
    private val storages: List<RemoteStorage>,
    private val credentials: (String) -> SecretStore,
    private val local: LocalCopies,
    private val categories: CategoryRepository,
    private val roots: ManageRoots,
    private val scan: ScanLibrary,
    private val clock: Clock,
) {
    fun observe(): Flow<List<RemoteSource>> = repository.observe()

    suspend fun all(): List<RemoteSource> = repository.all()

    private val states = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloads: StateFlow<Map<String, DownloadState>> = states.asStateFlow()
    private val running = Mutex()
    @kotlin.concurrent.Volatile private var cancelled: String? = null

    private fun storage(provider: RemoteProvider) = storages.first { it.provider == provider }

    suspend fun folders(provider: RemoteProvider, credentials: RemoteCredentials, base: String, address: String?): List<RemoteDirectory> =
        storage(provider).folders(credentials, base, address)

    /** The courses a remote folder holds before adding it (to choose some), from what is there now. */
    suspend fun preview(provider: RemoteProvider, credentials: RemoteCredentials, address: String, layout: RootLayout, category: CategoryKey, name: String): RemotePreview {
        val files = storage(provider).files(credentials, address)
        val exclusions = exclusions(category)
        return RemotePreview(
            RemoteCourses.effectiveLayout(files, layout, exclusions),
            RemoteCourses.courses(files, emptyMap(), layout, exclusions, name),
        )
    }

    /** Adds the folder: its credentials to the keyring, its destination as a folder of the library, and downloads. */
    suspend fun add(draft: NewRemoteSource, secret: RemoteCredentials): RemoteSource {
        if (!local.prepare(draft.destination)) throw IllegalStateException("destination")
        val id = Uuid.random().toString()
        credentials(id).write(RemoteCredentials.encode(secret))
        val root = roots.add(draft.category, draft.destination, draft.layout)
        val source = RemoteSource(
            id, draft.provider, draft.name, draft.account, draft.address, draft.location, draft.category, root.key, draft.layout,
            draft.destination, draft.mode, draft.selected, draft.wifiOnly, null, null,
        )
        repository.save(source)
        return source
    }

    /** The folder's courses now, with what is already downloaded. */
    suspend fun courses(id: String): List<RemoteCourse> {
        val source = repository.get(id) ?: return emptyList()
        val secret = secret(source) ?: throw RemoteError.Unauthorized()
        val files = storage(source.provider).files(secret, source.address)
        return RemoteCourses.courses(files, local.list(source.destination), source.layout, exclusions(source.category), source.name)
    }

    suspend fun choose(id: String, mode: DownloadMode, selected: Set<String>) {
        val source = repository.get(id) ?: return
        repository.save(source.copy(mode = mode, selected = selected))
    }

    suspend fun setWifiOnly(id: String, wifiOnly: Boolean) {
        val source = repository.get(id) ?: return
        repository.save(source.copy(wifiOnly = wifiOnly))
    }

    /** Deletes the downloaded files of courses no longer chosen (the user asked); returns how many. */
    suspend fun freeSpace(id: String): Int {
        val source = repository.get(id) ?: return 0
        val copies = local.list(source.destination)
        // Added with the layout its preview showed; AUTO only for folders added before seeing it.
        val effective = if (source.layout == RootLayout.AUTO) RootLayout.LIBRARY else source.layout
        val gone = RemoteCourses.notChosen(source, copies.keys, effective)
        local.delete(source.destination, gone)
        if (gone.isNotEmpty()) scan.category(source.category)
        return gone.size
    }

    /** Stops downloading it; [deleteFiles] also removes what was downloaded. Its courses leave the library. */
    suspend fun remove(id: String, deleteFiles: Boolean) {
        val source = repository.get(id) ?: return
        cancel(id)
        running.withLock {
            repository.delete(id)
            credentials(id).clear()
            categories.deleteRoot(source.rootKey)
            if (deleteFiles) local.deleteAll(source.destination)
            states.update { it - id }
        }
    }

    fun cancel(id: String) {
        cancelled = id
    }

    /**
     * Downloads what is missing or changed in every folder ([onUnmetered]: false skips "Wi-Fi only" ones), one file
     * at a time, then scans what arrived. Returns how many files were downloaded.
     */
    suspend fun downloadAll(onUnmetered: Boolean = true): Int =
        repository.all().filter { onUnmetered || !it.wifiOnly }.sumOf { download(it.id) }

    suspend fun download(id: String): Int = running.withLock {
        val source = repository.get(id) ?: return@withLock 0
        cancelled = null
        states.update { it + (id to DownloadState.Listing) }
        var done = 0
        val outcome: DownloadState = try {
            val secret = secret(source) ?: throw RemoteError.Unauthorized()
            if (!local.prepare(source.destination)) throw IllegalStateException("destination")
            val storage = storage(source.provider)
            val files = storage.files(secret, source.address)
            val wanted = RemoteCourses.toDownload(source, files, local.list(source.destination), exclusions(source.category))
            val total = wanted.sumOf { it.size }
            val free = local.freeSpace(source.destination)
            if (free != null && free < total + SPACE_MARGIN) throw NoSpace()
            var doneBytes = 0L
            for (file in wanted) {
                if (cancelled == id) break
                states.update { it + (id to DownloadState.Downloading(file.path.value, wanted.size, done, total, doneBytes)) }
                val before = doneBytes
                storage.download(secret, file, local.target(source.destination, file.path)) { written ->
                    states.update { it + (id to DownloadState.Downloading(file.path.value, wanted.size, done, total, before + written)) }
                }
                doneBytes = before + file.size
                done++
            }
            DownloadState.Idle
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.warn("remote", "download of ${source.name} failed", e)
            DownloadState.Failed(reasonOf(e))
        }
        repository.save(
            source.copy(
                lastSyncAt = if (outcome is DownloadState.Failed) source.lastSyncAt else clock.now(),
                lastError = (outcome as? DownloadState.Failed)?.reason,
            ),
        )
        states.update { it + (id to outcome) }
        // What arrived becomes courses in the library.
        if (done > 0) scan.category(source.category)
        done
    }

    private suspend fun secret(source: RemoteSource) = RemoteCredentials.decode(credentials(source.id).read())

    private suspend fun exclusions(category: CategoryKey) = ExclusionRules(categories.get(category)?.excludePatterns.orEmpty())

    private class NoSpace : Exception("no space")

    companion object {
        /** Kept free on the device besides what is downloaded. */
        const val SPACE_MARGIN = 500L shl 20

        fun reasonOf(e: Exception): String = when (e) {
            is NoSpace -> "space"
            is RemoteError.Unauthorized -> "credentials"
            is RemoteError.Rejected -> if (e.status == 401 || e.status == 403) "credentials" else e.reportCode()
            is RemoteError.Unreachable -> "unreachable"
            is IllegalStateException -> if (e.message == "destination") "destination" else "error"
            else -> "error"
        }
    }
}

/** What adding a folder would bring: the layout it will have and its courses. */
data class RemotePreview(val layout: RootLayout, val courses: List<RemoteCourse>)
