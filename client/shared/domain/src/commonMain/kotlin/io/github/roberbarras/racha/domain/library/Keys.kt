package io.github.roberbarras.racha.domain.library

import kotlin.jvm.JvmInline
import kotlin.uuid.Uuid

/** Stable category identifier, identical on every device: fixed for built-ins, a UUID for user categories. */
@JvmInline
value class CategoryKey(val value: String) {
    init {
        require(PATTERN.matches(value)) { "Invalid category key: $value" }
    }

    val isBuiltIn: Boolean get() = this in BUILT_IN

    companion object {
        private val PATTERN = Regex("[a-z0-9][a-z0-9-]{1,63}")
        val COURSES = CategoryKey("courses")
        val SERIES = CategoryKey("series")
        val LIVE = CategoryKey("live")
        val BUILT_IN = listOf(COURSES, SERIES, LIVE)

        fun random(): CategoryKey = CategoryKey(Uuid.random().toString())
    }
}

/** Stable, synced identifier of one root folder of a category (ADR-0004); its local path is per device. */
@JvmInline
value class RootKey(val value: String) {
    init {
        require(Uuid.parseOrNull(value) != null) { "Invalid root key: $value" }
    }

    companion object {
        fun random(): RootKey = RootKey(Uuid.random().toString())
    }
}
