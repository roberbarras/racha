package io.github.roberbarras.racha.domain.library

import kotlin.time.Instant

/**
 * A user-visible shelf (Courses, Series, Live, or user-defined). Categories have no "type": every folder is analysed
 * with all heuristics and its [LibraryItem.kind] records the detected structure.
 *
 * @property name null for built-ins means "use the localized default label"; a rename stores the user's text.
 * @property excludePatterns glob-like patterns relative to each root that the scanner skips (spec §3.2.4, §9).
 */
data class Category(
    val key: CategoryKey,
    val name: String?,
    val icon: String,
    val color: String,
    val isPrimary: Boolean,
    val order: Int,
    val visibleInStudy: Boolean,
    val visibleInLeisure: Boolean,
    val excludePatterns: List<String>,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    init {
        require(name == null || name.isNotBlank()) { "Category name must not be blank" }
        require(COLOR.matches(color)) { "Invalid category color: $color" }
    }

    fun isVisibleIn(mode: Mode): Boolean = when (mode) {
        Mode.STUDY -> visibleInStudy
        Mode.LEISURE -> visibleInLeisure
    }

    companion object {
        private val COLOR = Regex("#[0-9A-Fa-f]{6}")
        val DEFAULT_EXCLUDES = listOf("temp/")

        /** Built-in categories created on first run. Courses is primary (spec §2.2 opens the primary tab). */
        fun builtIns(now: Instant): List<Category> = listOf(
            builtIn(CategoryKey.COURSES, "school", "#E8812A", order = 0, primary = true, study = true, leisure = false, now),
            builtIn(CategoryKey.SERIES, "tv", "#3A86FF", order = 1, primary = false, study = false, leisure = true, now),
            builtIn(CategoryKey.LIVE, "broadcast", "#2D6A4F", order = 2, primary = false, study = true, leisure = true, now),
        )

        private fun builtIn(
            key: CategoryKey,
            icon: String,
            color: String,
            order: Int,
            primary: Boolean,
            study: Boolean,
            leisure: Boolean,
            now: Instant,
        ) = Category(key, null, icon, color, primary, order, study, leisure, DEFAULT_EXCLUDES, now, now)
    }
}

/** Study shows learning categories, Leisure entertainment ones (spec §2.2, §9 "Modo"). */
enum class Mode { STUDY, LEISURE }

/**
 * One root folder of a category. [key] is synced and links the same library across devices; [location] is the
 * device-local URI (`file://…` today, `content://…` if SAF is added, ADR-0005) and is never synced.
 */
data class CategoryRoot(
    val key: RootKey,
    val categoryKey: CategoryKey,
    val location: String,
    val order: Int,
)
