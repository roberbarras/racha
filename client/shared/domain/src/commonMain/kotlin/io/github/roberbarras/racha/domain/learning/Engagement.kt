package io.github.roberbarras.racha.domain.learning

import io.github.roberbarras.racha.domain.library.ContentId
import kotlin.time.Instant

/** Timestamped note inside a video (spec §2.2 "Notas y marcadores", §4.2). */
data class Bookmark(
    val id: String,
    val elementId: ContentId,
    val timestampSec: Long,
    val note: String,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    init {
        require(id.isNotBlank()) { "Bookmark id must not be blank" }
        require(timestampSec >= 0) { "timestampSec must be >= 0" }
    }
}

/** One viewing session of an element (spec §2.2 "Historial"). */
data class HistoryEntry(
    val id: String,
    val elementId: ContentId,
    val watchedAt: Instant,
    val positionAtEndSec: Long,
)

/** "Mi lista": either a whole course/series or a single element. */
sealed interface FavoriteTarget {
    val id: ContentId

    data class Item(override val id: ContentId) : FavoriteTarget

    data class Element(override val id: ContentId) : FavoriteTarget
}

data class Favorite(val target: FavoriteTarget, val createdAt: Instant)

/** Locally aggregated metric for the personal statistics screen, e.g. `watchSeconds` per `2026-11-12`. */
data class LocalStat(val metric: String, val period: String, val value: Double, val updatedAt: Instant)
