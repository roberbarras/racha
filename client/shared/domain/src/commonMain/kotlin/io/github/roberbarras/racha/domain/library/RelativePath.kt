package io.github.roberbarras.racha.domain.library

import io.github.roberbarras.racha.domain.shared.normalizeNfc

/**
 * A path relative to a category root, normalised so the same file yields the same value on every device
 * (ADR-0004): Unicode NFC, `/` separators, no empty or `.` segments, case preserved. `..` is rejected.
 */
class RelativePath private constructor(val value: String) {

    val segments: List<String> get() = if (value.isEmpty()) emptyList() else value.split('/')

    val isRoot: Boolean get() = value.isEmpty()

    /** Last segment, or "" for the root. */
    val name: String get() = value.substringAfterLast('/')

    val parent: RelativePath get() = if ('/' in value) RelativePath(value.substringBeforeLast('/')) else ROOT

    /** Lower-case extension of the last segment, or null (also for dot-files such as `.nomedia`). */
    val extension: String?
        get() = name.lastIndexOf('.').takeIf { it > 0 && it < name.length - 1 }?.let { name.substring(it + 1).lowercase() }

    fun withoutExtension(): RelativePath {
        val ext = extension ?: return this
        return RelativePath(value.dropLast(ext.length + 1))
    }

    fun resolve(child: String): RelativePath = of(if (isRoot) child else "$value/$child")

    override fun equals(other: Any?): Boolean = other is RelativePath && other.value == value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value

    companion object {
        val ROOT = RelativePath("")

        fun of(raw: String): RelativePath {
            val segments = normalizeNfc(raw).replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
            require(segments.none { it == ".." }) { "Relative paths must not contain '..': $raw" }
            return RelativePath(segments.joinToString("/"))
        }
    }
}
