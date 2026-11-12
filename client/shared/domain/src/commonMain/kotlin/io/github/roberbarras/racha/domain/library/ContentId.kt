package io.github.roberbarras.racha.domain.library

import io.github.roberbarras.racha.domain.shared.Sha256
import kotlin.jvm.JvmInline

/**
 * Device-independent identifier of library content (spec §3.4, ADR-0004):
 * `SHA-256(categoryKey "/" rootKey "/" path)` as lowercase hex, where the hashed path follows the rules below.
 */
@JvmInline
value class ContentId(val value: String) {
    init {
        require(HEX_64.matches(value)) { "Invalid content id: $value" }
    }

    override fun toString(): String = value

    companion object {
        private val HEX_64 = Regex("[0-9a-f]{64}")

        /** Videos drop their extension, so `NNN Title.mp4` and its `NNN Title_xx.srt` share a stem. */
        fun forVideo(category: CategoryKey, root: RootKey, path: RelativePath): ContentId =
            hash(category, root, path.withoutExtension().value)

        /** Resources keep the extension, so `003 X.html` never collides with the video `003 X.mp4`. */
        fun forResource(category: CategoryKey, root: RootKey, path: RelativePath): ContentId =
            hash(category, root, path.value)

        fun forSubtitle(category: CategoryKey, root: RootKey, path: RelativePath): ContentId =
            hash(category, root, path.value)

        /**
         * Folders (library items, sections) end with `/`, so the folder `001 Intro` and the video
         * `001 Intro.mp4` next to it get different ids. The category root itself hashes as `""` + `/`.
         */
        fun forFolder(category: CategoryKey, root: RootKey, path: RelativePath): ContentId =
            hash(category, root, path.value + "/")

        /** Synthetic groups that have no folder of their own (e.g. a date group inside a channel). */
        fun forGroup(parent: ContentId, groupKey: String): ContentId =
            ContentId(Sha256.hex("${parent.value}#$groupKey".encodeToByteArray()))

        private fun hash(category: CategoryKey, root: RootKey, path: String): ContentId =
            ContentId(Sha256.hex("${category.value}/${root.value}/$path".encodeToByteArray()))
    }
}
