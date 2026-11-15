package io.github.roberbarras.racha.application.browse

import io.github.roberbarras.racha.application.library.RootRelinker
import io.github.roberbarras.racha.application.sync.RemoteRoot
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
    private val remoteRoots: suspend (CategoryKey) -> List<RemoteRoot> = { emptyList() },
    private val relinker: RootRelinker = RootRelinker { _, _ -> },
) {
    suspend fun roots(category: CategoryKey): List<CategoryRoot> = categories.roots(category)

    /** Folders of this category on the account's other devices that are not linked here yet (ADR-0004). */
    suspend fun linkable(category: CategoryKey): List<RemoteRoot> {
        val local = categories.roots(category).map { it.key.value }.toSet()
        return remoteRoots(category).filter { it.key !in local }
    }

    /** Adds a folder; [linkTo] makes it the same root as that folder on another device, so ids (and progress) match. */
    suspend fun add(category: CategoryKey, location: String, linkTo: RootKey? = null): CategoryRoot {
        categories.roots(category).firstOrNull { it.location == location }?.let { return it }
        val root = CategoryRoot(linkTo ?: RootKey.random(), category, location, categories.roots(category).size)
        categories.upsertRoot(root)
        scan.category(category)
        return root
    }

    /** Links an existing folder to the same one on another device: its content takes that device's ids. */
    suspend fun link(root: CategoryRoot, to: RootKey) {
        relinker.relink(root, to)
        scan.category(root.categoryKey)
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
