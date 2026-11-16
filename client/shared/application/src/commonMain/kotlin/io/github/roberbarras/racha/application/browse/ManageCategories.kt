package io.github.roberbarras.racha.application.browse

import io.github.roberbarras.racha.application.library.CategoryRepository
import io.github.roberbarras.racha.application.library.scan.ScanLibrary
import io.github.roberbarras.racha.application.shared.Clock
import io.github.roberbarras.racha.domain.library.Category
import io.github.roberbarras.racha.domain.library.CategoryKey

/**
 * Categories in Settings (spec §9 "Categorías" and "Modo"): create, rename, delete, reorder, choose the primary one,
 * which mode shows each and what the scanner skips. Built-ins can be renamed and hidden but not deleted.
 */
class ManageCategories(
    private val categories: CategoryRepository,
    private val scan: ScanLibrary,
    private val clock: Clock,
) {
    suspend fun create(name: String): Category {
        val all = categories.all()
        val now = clock.now()
        val category = Category(
            key = CategoryKey.random(),
            name = name.trim().also { require(it.isNotEmpty()) { "A category needs a name" } },
            icon = "folder",
            color = PALETTE[all.size % PALETTE.size],
            isPrimary = all.none { it.isPrimary },
            order = (all.maxOfOrNull { it.order } ?: -1) + 1,
            visibleInStudy = true,
            visibleInLeisure = true,
            excludePatterns = Category.DEFAULT_EXCLUDES,
            createdAt = now,
            updatedAt = now,
        )
        categories.upsert(category)
        return category
    }

    /** A blank name gives a built-in its translated default back; user categories keep their old name. */
    suspend fun rename(key: CategoryKey, name: String) = update(key) { category ->
        val trimmed = name.trim()
        when {
            trimmed.isNotEmpty() -> category.copy(name = trimmed)
            key.isBuiltIn -> category.copy(name = null)
            else -> category
        }
    }

    /** Deletes a user category with what was indexed in it; progress stays (spec §3.4). */
    suspend fun delete(key: CategoryKey) {
        require(!key.isBuiltIn) { "Built-in categories can be hidden, not deleted" }
        val wasPrimary = categories.get(key)?.isPrimary == true
        categories.delete(key)
        if (wasPrimary) categories.all().minByOrNull { it.order }?.let { categories.setPrimary(it.key, clock.now()) }
    }

    /** Moves a category one place up (-1) or down (+1). */
    suspend fun move(key: CategoryKey, direction: Int) {
        val ordered = categories.all().sortedBy { it.order }
        val index = ordered.indexOfFirst { it.key == key }
        val other = ordered.getOrNull(index + direction) ?: return
        val current = ordered[index]
        val now = clock.now()
        categories.upsert(current.copy(order = other.order, updatedAt = now))
        categories.upsert(other.copy(order = current.order, updatedAt = now))
    }

    suspend fun setPrimary(key: CategoryKey) = categories.setPrimary(key, clock.now())

    suspend fun setVisibility(key: CategoryKey, study: Boolean, leisure: Boolean) =
        update(key) { it.copy(visibleInStudy = study, visibleInLeisure = leisure) }

    /** One pattern per entry (`temp/`, `*.part`…); the category is scanned again so the change shows at once. */
    suspend fun setExcludes(key: CategoryKey, patterns: List<String>) {
        update(key) { it.copy(excludePatterns = patterns.map(String::trim).filter(String::isNotEmpty).distinct()) }
        scan.category(key)
    }

    private suspend fun update(key: CategoryKey, change: (Category) -> Category) {
        val current = categories.get(key) ?: return
        val updated = change(current)
        if (updated != current) categories.upsert(updated.copy(updatedAt = clock.now()))
    }

    private companion object {
        val PALETTE = listOf("#7B61FF", "#E05A47", "#2A9D8F", "#C9A227", "#3A86FF", "#D65DB1")
    }
}
