package io.github.roberbarras.racha.application.library

import io.github.roberbarras.racha.application.shared.Clock
import io.github.roberbarras.racha.domain.library.Category
import io.github.roberbarras.racha.domain.library.CategoryKey
import io.github.roberbarras.racha.domain.library.CategoryRoot
import io.github.roberbarras.racha.domain.library.RootKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class EnsureBuiltInCategoriesTest {
    private val now = Instant.parse("2026-11-12T10:00:00Z")
    private val repository = InMemoryCategoryRepository()
    private val ensure = EnsureBuiltInCategories(repository, Clock { now })

    @Test
    fun createsTheThreeBuiltInsWithCoursesAsPrimary() = runTest {
        ensure()

        assertEquals(CategoryKey.BUILT_IN, repository.all().map { it.key })
        assertEquals(listOf(CategoryKey.COURSES), repository.all().filter { it.isPrimary }.map { it.key })
    }

    @Test
    fun isIdempotentAndKeepsUserChanges() = runTest {
        ensure()
        val renamed = repository.get(CategoryKey.SERIES)!!.copy(name = "Mis series", order = 5)
        repository.upsert(renamed)

        ensure()

        assertEquals(3, repository.all().size)
        assertEquals(renamed, repository.get(CategoryKey.SERIES))
    }

    @Test
    fun doesNotStealPrimaryFromAUserCategory() = runTest {
        val own = Category.builtIns(now).first().copy(key = CategoryKey("my-talks"), name = "Charlas", isPrimary = true)
        repository.upsert(own)

        ensure()

        assertEquals(listOf(own.key), repository.all().filter { it.isPrimary }.map { it.key })
    }
}

class InMemoryCategoryRepository : CategoryRepository {
    private val state = MutableStateFlow<List<Category>>(emptyList())
    private val roots = mutableListOf<CategoryRoot>()

    override fun observeAll(): Flow<List<Category>> = state

    override suspend fun all() = state.value.sortedBy { it.order }

    override suspend fun get(key: CategoryKey) = state.value.firstOrNull { it.key == key }

    override suspend fun upsert(category: Category) {
        state.value = state.value.filterNot { it.key == category.key } + category
    }

    override suspend fun delete(key: CategoryKey) {
        state.value = state.value.filterNot { it.key == key }
    }

    override suspend fun setPrimary(key: CategoryKey, at: Instant) {
        state.value = state.value.map { it.copy(isPrimary = it.key == key, updatedAt = at) }
    }

    override suspend fun roots(category: CategoryKey) = roots.filter { it.categoryKey == category }

    override suspend fun upsertRoot(root: CategoryRoot) {
        roots.removeAll { it.key == root.key }
        roots += root
    }

    override suspend fun deleteRoot(key: RootKey) {
        roots.removeAll { it.key == key }
    }
}
