package io.github.roberbarras.racha.application.library

import io.github.roberbarras.racha.domain.library.Category
import io.github.roberbarras.racha.domain.library.CategoryKey
import io.github.roberbarras.racha.domain.library.CategoryRoot
import io.github.roberbarras.racha.domain.library.ContentId
import io.github.roberbarras.racha.domain.library.LibraryItem
import io.github.roberbarras.racha.domain.library.PlayableElement
import io.github.roberbarras.racha.domain.library.RootKey
import io.github.roberbarras.racha.domain.library.Section
import io.github.roberbarras.racha.domain.library.Subtitle
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

/** Outbound port: categories and their root folders. */
interface CategoryRepository {
    fun observeAll(): Flow<List<Category>>

    suspend fun all(): List<Category>

    suspend fun get(key: CategoryKey): Category?

    suspend fun upsert(category: Category)

    /** Deletes the category, its roots and everything indexed under it. */
    suspend fun delete(key: CategoryKey)

    /** Makes [key] the only primary category (spec §2.2, §9), atomically. */
    suspend fun setPrimary(key: CategoryKey, at: Instant)

    suspend fun roots(category: CategoryKey): List<CategoryRoot>

    suspend fun upsertRoot(root: CategoryRoot)

    suspend fun deleteRoot(key: RootKey)
}

/** Outbound port: the indexed library (items, sections, elements, subtitles). */
interface LibraryRepository {
    fun observeItems(category: CategoryKey): Flow<List<LibraryItem>>

    suspend fun item(id: ContentId): LibraryItem?

    suspend fun upsertItem(item: LibraryItem)

    suspend fun sections(item: ContentId): List<Section>

    suspend fun upsertSection(section: Section)

    fun observeElements(item: ContentId): Flow<List<PlayableElement>>

    suspend fun elements(item: ContentId): List<PlayableElement>

    suspend fun element(id: ContentId): PlayableElement?

    suspend fun upsertElement(element: PlayableElement)

    /** Elements already indexed with this content hash, to re-link progress after a move (spec §3.4). */
    suspend fun elementsWithHash(fileHash: String): List<PlayableElement>

    /** Everything indexed under one root (archived included): the baseline of an incremental scan. */
    suspend fun itemsInRoot(category: CategoryKey, root: RootKey): List<LibraryItem>

    suspend fun elementsInRoot(category: CategoryKey, root: RootKey): List<PlayableElement>

    /** Archived elements with a known hash and this exact size: the only candidates a new file can re-link to. */
    suspend fun archivedElementsWithSize(fileSize: Long): List<PlayableElement>

    /** Present elements without hash that carry user data (progress, bookmarks, favorites) worth re-linking later. */
    suspend fun elementsNeedingHash(limit: Int): List<PlayableElement>

    /**
     * The file of [from] reappeared as [to] (moved or renamed): its progress, bookmarks, history and favorites move to
     * [to] and the archived [from] element is removed, atomically.
     */
    suspend fun relinkElement(from: ContentId, to: ContentId)

    suspend fun subtitles(element: ContentId): List<Subtitle>

    suspend fun upsertSubtitle(subtitle: Subtitle)

    suspend fun deleteSubtitle(id: ContentId)
}
