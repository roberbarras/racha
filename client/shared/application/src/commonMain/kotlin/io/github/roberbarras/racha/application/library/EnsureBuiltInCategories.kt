package io.github.roberbarras.racha.application.library

import io.github.roberbarras.racha.application.shared.Clock
import io.github.roberbarras.racha.domain.library.Category

/**
 * First-run setup: creates Courses, Series and Live if missing. Idempotent; never touches existing (possibly
 * renamed or reordered) categories, and only proposes a primary when none exists.
 */
class EnsureBuiltInCategories(private val categories: CategoryRepository, private val clock: Clock) {

    suspend operator fun invoke() {
        val existing = categories.all()
        val existingKeys = existing.map { it.key }.toSet()
        val hasPrimary = existing.any { it.isPrimary }
        Category.builtIns(clock.now())
            .filter { it.key !in existingKeys }
            .forEach { categories.upsert(if (hasPrimary) it.copy(isPrimary = false) else it) }
    }
}
