package io.github.roberbarras.racha.application.browse

import io.github.roberbarras.racha.application.library.CategoryRepository
import io.github.roberbarras.racha.application.library.LibraryRepository
import io.github.roberbarras.racha.application.library.scan.ScanLibrary
import io.github.roberbarras.racha.application.preferences.Preferences
import io.github.roberbarras.racha.application.shared.Clock
import io.github.roberbarras.racha.domain.library.CategoryKey
import io.github.roberbarras.racha.domain.library.CategoryRoot
import io.github.roberbarras.racha.domain.library.Mode
import io.github.roberbarras.racha.domain.library.RootKey
import io.github.roberbarras.racha.domain.preferences.Settings

/** Folders of each category (spec §9 "múltiples rutas raíz"): adding one indexes it right away. */
class ManageRoots(
    private val categories: CategoryRepository,
    private val library: LibraryRepository,
    private val scan: ScanLibrary,
    private val clock: Clock,
) {
    suspend fun roots(category: CategoryKey): List<CategoryRoot> = categories.roots(category)

    suspend fun add(category: CategoryKey, location: String): CategoryRoot {
        categories.roots(category).firstOrNull { it.location == location }?.let { return it }
        val root = CategoryRoot(RootKey.random(), category, location, categories.roots(category).size)
        categories.upsertRoot(root)
        scan.category(category)
        return root
    }

    /** Stops indexing a folder; what it contained stays archived with its progress (spec §3.4). */
    suspend fun remove(root: CategoryRoot) {
        val now = clock.now()
        library.elementsInRoot(root.categoryKey, root.key).filterNot { it.archived }
            .forEach { library.upsertElement(it.copy(archived = true, updatedAt = now)) }
        library.itemsInRoot(root.categoryKey, root.key).filterNot { it.archived }
            .forEach { library.upsertItem(it.copy(archived = true, updatedAt = now)) }
        categories.deleteRoot(root.key)
    }
}

/** Study / Leisure switch (spec §2.2, §9). */
class SetMode(private val preferences: Preferences) {
    suspend operator fun invoke(mode: Mode) = preferences.set(Settings.Mode, mode)
}
