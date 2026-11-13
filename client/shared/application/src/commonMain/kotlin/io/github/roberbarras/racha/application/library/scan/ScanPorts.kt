package io.github.roberbarras.racha.application.library.scan

import io.github.roberbarras.racha.domain.library.MediaInfo
import io.github.roberbarras.racha.domain.library.RelativePath
import io.github.roberbarras.racha.domain.library.scan.CalendarDate
import io.github.roberbarras.racha.domain.library.scan.FileEntry
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

/**
 * Outbound port over a category root (ADR-0005). [location] is the root's device-local URI (`file://…`).
 * Implementations: java.nio on desktop and Android today; SAF could be added for a Play Store build.
 */
interface LibrarySource {
    /** Name of the root folder, used as the course title when the root itself is a course. */
    fun displayName(location: String): String

    /**
     * Every file and directory under [location], with paths relative to it. Directories for which [descend] returns
     * false are neither listed nor entered. A missing or unreadable root yields an empty list.
     */
    suspend fun list(location: String, descend: (RelativePath) -> Boolean): List<FileEntry>

    /** URI that players and viewers can open for a file of this root. */
    fun uri(location: String, path: RelativePath): String
}

/** Outbound port: SHA-256 of a file's content, streamed (spec §3.4 `fileHash`). */
fun interface FileHasher {
    suspend fun sha256(location: String, path: RelativePath): String
}

/** Outbound port: technical metadata (spec §3.3). Returns null when the file cannot be read as media. */
fun interface MediaProbe {
    suspend fun probe(location: String, path: RelativePath): MediaInfo?
}

/** Outbound port: renders a frame at [fraction] of the video into the thumbnail cache; returns its URI or null. */
fun interface ThumbnailRenderer {
    suspend fun render(location: String, path: RelativePath, durationSec: Long?, fraction: Double, cacheKey: String): String?
}

/** Outbound port: change notifications for a root (native file watcher). Emits after any create/delete/move/write. */
fun interface LibraryWatcher {
    fun changes(location: String): Flow<Unit>
}

/** Converts instants to the user's local calendar date (grouping by modification day). */
fun interface LocalDates {
    fun dateOf(instant: Instant): CalendarDate
}
