package io.github.roberbarras.racha.domain.library

import kotlin.time.Instant

/** Structure detected by the scanner for a folder, regardless of the category it lives in. */
enum class StructureKind {
    /** `NN - Section/NNN Element` style. */
    COURSE,

    /** Seasons/episodes (`Season X`, `Temporada X`, `S01E02`). */
    SERIES,

    /** Grouped by dates found in names or modification times. */
    LIVE,

    /** Loose files at the root of a category. */
    LOOSE,
}

/**
 * A course, series or channel. Totals and progress are denormalised so the Home screen reads one row per item
 * instead of aggregating elements on every frame (cheaper reads, less battery).
 *
 * @property titleOverride / [orderOverride] user edits (spec §3.2.6); rescans never overwrite them.
 * @property archived the folder disappeared from disk but the item keeps its progress (spec §3.4).
 */
data class LibraryItem(
    val id: ContentId,
    val categoryKey: CategoryKey,
    val rootKey: RootKey,
    val relativePath: RelativePath,
    val kind: StructureKind,
    val title: String,
    val titleOverride: String?,
    val description: String?,
    val posterPath: String?,
    val order: Int,
    val orderOverride: Int?,
    val totalItems: Int,
    val totalDurationSec: Long,
    val progressPct: Double,
    val archived: Boolean,
    val firstSeenAt: Instant,
    val updatedAt: Instant,
) {
    init {
        require(progressPct in 0.0..100.0) { "progressPct out of range: $progressPct" }
        require(totalItems >= 0 && totalDurationSec >= 0)
    }

    val displayTitle: String get() = titleOverride ?: title
    val effectiveOrder: Int get() = orderOverride ?: order
}

enum class SectionKind { MODULE, SEASON, DATE, LOOSE }

/** A module (`NN - Title`), season, date group or the implicit loose group of a [LibraryItem]. */
data class Section(
    val id: ContentId,
    val libraryItemId: ContentId,
    val kind: SectionKind,
    val title: String,
    val titleOverride: String?,
    val order: Int,
    val orderOverride: Int?,
    val updatedAt: Instant,
) {
    val displayTitle: String get() = titleOverride ?: title
    val effectiveOrder: Int get() = orderOverride ?: order
}
