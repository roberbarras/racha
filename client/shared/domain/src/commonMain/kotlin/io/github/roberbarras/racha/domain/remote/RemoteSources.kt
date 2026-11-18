package io.github.roberbarras.racha.domain.remote

import io.github.roberbarras.racha.domain.library.CategoryKey
import io.github.roberbarras.racha.domain.library.RelativePath
import io.github.roberbarras.racha.domain.library.RootKey
import io.github.roberbarras.racha.domain.library.RootLayout
import io.github.roberbarras.racha.domain.library.scan.ExclusionRules
import io.github.roberbarras.racha.domain.library.scan.FileEntry
import io.github.roberbarras.racha.domain.library.scan.LibraryLayout
import kotlin.time.Instant

/** Where remote courses come from (H19). WebDAV covers Nextcloud (and ownCloud and other WebDAV servers). */
enum class RemoteProvider { WEBDAV, GOOGLE_DRIVE }

/** Everything, or only the courses the user picked (new courses are then left alone). */
enum class DownloadMode { ALL, SELECTED }

/**
 * A remote folder whose courses are downloaded to this device (H19). The download goes to [destination], an ordinary
 * folder of the library (root [rootKey] of [category], with [layout]), so once there everything else (scanning,
 * playing, progress, sync) works as with any local folder.
 *
 * [address] is how the provider finds the folder: the WebDAV URL of the folder, or the Drive folder id. [location] is
 * what the user sees ("cloud.example.com · /Cursos", "Drive · Cursos"). Credentials are not here: they are in the
 * keyring under [id].
 */
data class RemoteSource(
    val id: String,
    val provider: RemoteProvider,
    val name: String,
    val account: String,
    val address: String,
    val location: String,
    val category: CategoryKey,
    val rootKey: RootKey,
    val layout: RootLayout,
    val destination: String,
    val mode: DownloadMode,
    /** Course keys (see [RemoteCourses.keyOf]) picked in [DownloadMode.SELECTED]. */
    val selected: Set<String>,
    /** Android: download only on unmetered networks. */
    val wifiOnly: Boolean,
    val lastSyncAt: Instant?,
    val lastError: String?,
)

/** A file in the remote folder; [ref] is what the provider needs to fetch it (URL or Drive file id). */
data class RemoteFile(val path: RelativePath, val size: Long, val modifiedAt: Instant, val ref: String)

/** A local copy as it is now, to know what is missing or outdated. */
data class LocalCopy(val size: Long, val modifiedAt: Instant)

/** One course, series or channel of the remote folder, with what downloading it means. */
data class RemoteCourse(
    val key: String,
    val title: String,
    val files: Int,
    val bytes: Long,
    /** Bytes already here and up to date. */
    val downloadedBytes: Long,
) {
    val complete: Boolean get() = downloadedBytes >= bytes
}

object RemoteCourses {
    /** Two seconds of tolerance: some servers and file systems keep times with less precision. */
    private const val TIME_TOLERANCE_MS = 2_000

    private fun entries(files: List<RemoteFile>): List<FileEntry> {
        val dirs = files.flatMap { f -> f.path.value.split('/').dropLast(1).runningReduce { a, b -> "$a/$b" } }.toSet()
        return dirs.map { FileEntry(RelativePath.of(it), isDirectory = true, size = 0, modifiedAt = Instant.fromEpochMilliseconds(0)) } +
            files.map { FileEntry(it.path, isDirectory = false, size = it.size, modifiedAt = it.modifiedAt) }
    }

    /** [layout] as it will be applied: AUTO decided from the names, like for a local folder (ADR-0013). */
    fun effectiveLayout(files: List<RemoteFile>, layout: RootLayout, exclusions: ExclusionRules): RootLayout =
        if (layout == RootLayout.AUTO) LibraryLayout.guess(entries(files), exclusions) else layout

    /**
     * Which course a file belongs to: its top-level folder in a library ("" for files lying in the root, the
     * "Loose files" item), "" for everything when the folder is one course.
     */
    fun keyOf(path: RelativePath, layout: RootLayout): String =
        if (layout == RootLayout.SINGLE_ITEM || '/' !in path.value) "" else path.value.substringBefore('/')

    fun isUpToDate(file: RemoteFile, local: LocalCopy?): Boolean =
        local != null && local.size == file.size && local.modifiedAt.toEpochMilliseconds() >= file.modifiedAt.toEpochMilliseconds() - TIME_TOLERANCE_MS

    /** The courses of the remote folder, in name order, with their size and how much is already here. */
    fun courses(
        files: List<RemoteFile>,
        local: Map<RelativePath, LocalCopy>,
        layout: RootLayout,
        exclusions: ExclusionRules,
        rootName: String,
    ): List<RemoteCourse> {
        val effective = effectiveLayout(files, layout, exclusions)
        return wanted(files, exclusions).groupBy { keyOf(it.path, effective) }.map { (key, group) ->
            RemoteCourse(
                key = key,
                title = if (effective == RootLayout.SINGLE_ITEM) rootName else key,
                files = group.size,
                bytes = group.sumOf { it.size },
                downloadedBytes = group.filter { isUpToDate(it, local[it.path]) }.sumOf { it.size },
            )
        }.sortedWith(compareBy({ it.key.isEmpty() }, { it.title.lowercase() }))
    }

    /** What to download now: the chosen courses' files that are missing or changed, small files first. */
    fun toDownload(source: RemoteSource, files: List<RemoteFile>, local: Map<RelativePath, LocalCopy>, exclusions: ExclusionRules): List<RemoteFile> {
        val effective = effectiveLayout(files, source.layout, exclusions)
        return wanted(files, exclusions)
            .filter { source.mode == DownloadMode.ALL || keyOf(it.path, effective) in source.selected }
            .filterNot { isUpToDate(it, local[it.path]) }
            // Subtitles, resources and posters first: a course is usable sooner, and a failure on a big video
            // does not leave the small files behind.
            .sortedWith(compareBy({ it.size }, { it.path.value }))
    }

    /** Local files of courses no longer chosen: what "free space" removes (only when the user asks). */
    fun notChosen(source: RemoteSource, local: Collection<RelativePath>, effective: RootLayout): List<RelativePath> =
        if (source.mode == DownloadMode.ALL) emptyList() else local.filter { keyOf(it, effective) !in source.selected }

    private fun wanted(files: List<RemoteFile>, exclusions: ExclusionRules): List<RemoteFile> = files.filterNot { f ->
        val parts = f.path.value.split('/')
        // Hidden or excluded folders and files (the same rules as scanning) and partial downloads are skipped.
        parts.any { it.startsWith('.') } || f.path.value.endsWith(PART) ||
            parts.dropLast(1).runningReduce { a, b -> "$a/$b" }.any { exclusions.excludes(RelativePath.of(it), isDirectory = true) } ||
            exclusions.excludes(f.path, isDirectory = false)
    }

    /** Suffix of a download in progress: renamed to its name once complete, so scanning never sees half a file. */
    const val PART = ".racha-part"
}
