package io.github.roberbarras.racha.domain.library

import kotlin.jvm.JvmInline
import kotlin.time.Instant

enum class ElementKind { VIDEO, RESOURCE }

/** Non-video elements shown in the embedded viewer (spec §5). */
enum class ResourceType(val extensions: Set<String>) {
    HTML(setOf("html", "htm")),
    URL(setOf("url")),
    TEXT(setOf("txt")),
    PDF(setOf("pdf")),
    ;

    companion object {
        fun forExtension(extension: String?): ResourceType? = entries.firstOrNull { extension in it.extensions }
    }
}

/** Technical metadata extracted while scanning (spec §3.3). Audio/subtitle tracks are read by the player. */
data class MediaInfo(
    val durationSec: Long?,
    val width: Int?,
    val height: Int?,
    val videoCodec: String?,
    val fps: Double?,
    val bitrateKbps: Int?,
) {
    val resolution: String? get() = if (width != null && height != null) "${width}x$height" else null

    companion object {
        val UNKNOWN = MediaInfo(null, null, null, null, null, null)
    }
}

/**
 * Something the user plays or views: a video or a resource. [fileSize] and [fileModifiedAt] drive incremental
 * scanning; [fileHash] (optional, computed in background) re-links progress when a file moves (spec §3.4).
 */
data class PlayableElement(
    val id: ContentId,
    val libraryItemId: ContentId,
    val sectionId: ContentId,
    val kind: ElementKind,
    val resourceType: ResourceType?,
    val title: String,
    val titleOverride: String?,
    val relativePath: RelativePath,
    val media: MediaInfo,
    val order: Int,
    val orderOverride: Int?,
    val archived: Boolean,
    val fileSize: Long,
    val fileModifiedAt: Instant,
    val fileHash: String?,
    val firstSeenAt: Instant,
    val updatedAt: Instant,
) {
    init {
        require((kind == ElementKind.RESOURCE) == (resourceType != null)) { "resourceType is set iff kind is RESOURCE" }
        require(fileHash == null || Regex("[0-9a-f]{64}").matches(fileHash)) { "fileHash must be SHA-256 hex" }
    }

    val displayTitle: String get() = titleOverride ?: title
    val effectiveOrder: Int get() = orderOverride ?: order
}

/** Lower-case BCP 47-like language code from a subtitle suffix (`_es`, `_pt-br`). */
@JvmInline
value class LanguageCode(val value: String) {
    init {
        require(Regex("[a-z]{2,3}(-[a-z0-9]{2,8})?").matches(value)) { "Invalid language code: $value" }
    }

    companion object {
        fun parse(raw: String): LanguageCode = LanguageCode(raw.lowercase().replace('_', '-'))
    }
}

/** External `.srt` subtitle of a video element (spec §4.1: only external SRT). */
data class Subtitle(
    val id: ContentId,
    val elementId: ContentId,
    val language: LanguageCode,
    val relativePath: RelativePath,
    val updatedAt: Instant,
)
