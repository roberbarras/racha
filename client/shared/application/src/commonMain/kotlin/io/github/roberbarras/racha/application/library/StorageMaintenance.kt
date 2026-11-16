package io.github.roberbarras.racha.application.library

import io.github.roberbarras.racha.application.learning.HistoryRepository
import io.github.roberbarras.racha.application.library.scan.KeepLibraryUpToDate

/** Outbound port: the folder where generated posters are cached. */
interface ThumbnailCache {
    /** How the posters in it are referenced (a `file://` prefix). */
    val locationPrefix: String

    /** Deletes every cached poster; returns the bytes freed. */
    suspend fun clear(): Long
}

/** Settings > Storage (spec §9): caches and local history. Nothing here touches the user's files. */
class StorageMaintenance(
    private val library: LibraryRepository,
    private val history: HistoryRepository,
    private val thumbnails: ThumbnailCache,
    private val keepUpToDate: KeepLibraryUpToDate,
) {
    /** Bytes freed. Posters are made again on the next refresh (if thumbnails are on). */
    suspend fun clearThumbnails(): Long {
        val freed = thumbnails.clear()
        library.forgetPostersUnder(thumbnails.locationPrefix)
        keepUpToDate.scanNow()
        return freed
    }

    /** Durations, resolutions and codecs are read again from every file. */
    suspend fun clearMetadata() {
        library.forgetFileFingerprints()
        keepUpToDate.scanNow()
    }

    /** History on this device only: history entries are never deleted on the server or other devices. */
    suspend fun clearHistory() = history.clear()
}
