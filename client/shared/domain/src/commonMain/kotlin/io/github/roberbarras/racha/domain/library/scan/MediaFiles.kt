package io.github.roberbarras.racha.domain.library.scan

import io.github.roberbarras.racha.domain.library.RelativePath
import io.github.roberbarras.racha.domain.library.ResourceType

/** What the scanner does with each file, by extension. */
object MediaFiles {
    /** Played by Media3/libmpv (spec §4.1: MP4, MKV, WEBM, AVI, MOV, HLS and others the engines support). */
    val VIDEO = setOf("mp4", "m4v", "mkv", "webm", "avi", "mov", "m3u8", "ts", "mts", "m2ts", "mpg", "mpeg", "wmv", "flv", "ogv", "3gp")

    const val SUBTITLE = "srt"

    /** Folder artwork used as the course/series poster (spec §3.3). */
    private val POSTER_NAMES = setOf("poster", "folder", "cover")
    private val IMAGE = setOf("jpg", "jpeg", "png", "webp")

    fun isVideo(path: RelativePath) = path.extension in VIDEO

    fun isSubtitle(path: RelativePath) = path.extension == SUBTITLE

    fun resourceType(path: RelativePath): ResourceType? = ResourceType.forExtension(path.extension)

    fun isPlayable(path: RelativePath) = isVideo(path) || resourceType(path) != null

    fun isPoster(path: RelativePath) =
        path.extension in IMAGE && path.withoutExtension().name.lowercase() in POSTER_NAMES
}
