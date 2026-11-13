package io.github.roberbarras.racha.application.library.scan

import io.github.roberbarras.racha.application.library.CategoryRepository
import io.github.roberbarras.racha.application.library.LibraryRepository

/**
 * Background job: computes the content hash (spec §3.4) only for present elements that carry user data, so their
 * progress can be re-linked if the file is later moved. Hashing every file of a large library would cost minutes of
 * full-disk reads and battery for no benefit. Returns how many elements were hashed.
 */
class HashEngagedElements(
    private val categories: CategoryRepository,
    private val library: LibraryRepository,
    private val hasher: FileHasher,
) {
    suspend operator fun invoke(limit: Int = 50): Int {
        val locations = categories.all().flatMap { categories.roots(it.key) }.associate { it.key to it.location }
        var hashed = 0
        for (element in library.elementsNeedingHash(limit)) {
            val item = library.item(element.libraryItemId) ?: continue
            val location = locations[item.rootKey] ?: continue // root not configured on this device
            val hash = runCatching { hasher.sha256(location, element.relativePath) }.getOrNull() ?: continue
            library.upsertElement(element.copy(fileHash = hash))
            hashed++
        }
        return hashed
    }
}
