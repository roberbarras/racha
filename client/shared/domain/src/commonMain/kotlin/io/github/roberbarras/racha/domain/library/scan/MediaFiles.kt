package io.github.roberbarras.racha.domain.library.scan

import io.github.roberbarras.racha.domain.library.RelativePath
import io.github.roberbarras.racha.domain.library.ResourceType

/** What the scanner does with each file, by extension. */
object MediaFiles {
    /** Played by Media3/libmpv (spec §4.1: MP4, MKV, WEBM, AVI, MOV, HLS and others the engines support). */
    val VIDEO = setOf("mp4", "m4v", "mkv", "webm", "avi", "mov", "m3u8", "ts", "mts", "m2ts", "mpg", "mpeg", "wmv", "flv", "ogv", "3gp")

    const val SUBTITLE = "srt"

    /** Folder artwork used as the course/series poster (spec §3.3): these names first, then any other image. */
    private val POSTER_NAMES = setOf("poster", "folder", "cover")
    private val IMAGE = setOf("jpg", "jpeg", "png", "webp")

    fun isVideo(path: RelativePath) = path.extension in VIDEO

    fun isSubtitle(path: RelativePath) = path.extension == SUBTITLE

    fun resourceType(path: RelativePath): ResourceType? = ResourceType.forExtension(path.extension)

    fun isPlayable(path: RelativePath) = isVideo(path) || resourceType(path) != null

    fun isPoster(path: RelativePath) = isImage(path) && path.withoutExtension().name.lowercase() in POSTER_NAMES

    /** An image file that can be a poster; hidden ones (`.DS_Store`'s kin, `._cover.jpg`) are not artwork. */
    fun isImage(path: RelativePath) = path.extension in IMAGE && !path.name.startsWith(".")

    /**
     * The poster among the files of an item's folder: `poster`/`folder`/`cover` if there is one, otherwise the first
     * image by name. With [anyImage] false (the loose files at a root) only the named ones count.
     */
    fun posterAmong(files: List<RelativePath>, anyImage: Boolean = true): RelativePath? {
        val images = files.filter(::isImage).sortedBy { it.name.lowercase() }
        return images.firstOrNull(::isPoster) ?: images.firstOrNull().takeIf { anyImage }
    }
}
