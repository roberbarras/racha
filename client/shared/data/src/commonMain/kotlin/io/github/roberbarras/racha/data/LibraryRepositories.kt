package io.github.roberbarras.racha.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import io.github.roberbarras.racha.application.library.CategoryRepository
import io.github.roberbarras.racha.application.library.LibraryRepository
import io.github.roberbarras.racha.data.db.Playable_element
import io.github.roberbarras.racha.data.db.RachaDatabase
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.time.Instant

class SqlCategoryRepository(private val db: RachaDatabase, private val io: CoroutineContext) : CategoryRepository {
    private val q get() = db.categoryQueries

    override fun observeAll(): Flow<List<Category>> =
        q.selectAll().asFlow().mapToList(io).map { rows -> rows.map { it.toDomain() } }

    override suspend fun all(): List<Category> = withContext(io) { q.selectAll().executeAsList().map { it.toDomain() } }

    override suspend fun get(key: CategoryKey): Category? =
        withContext(io) { q.selectByKey(key.value).executeAsOneOrNull()?.toDomain() }

    override suspend fun upsert(category: Category) = withContext(io) {
        val row = category.toRow()
        q.transaction {
            if (row.is_primary) q.clearPrimary(row.updated_at, row.key)
            q.insertOrIgnore(row)
            q.update(
                row.name, row.icon, row.color, row.is_primary, row.sort_order, row.visible_in_study,
                row.visible_in_leisure, row.exclude_patterns, row.created_at, row.updated_at, row.key,
            )
        }
    }

    override suspend fun delete(key: CategoryKey) = withContext(io) { q.delete(key.value); Unit }

    override suspend fun setPrimary(key: CategoryKey, at: Instant) = withContext(io) {
        q.transaction {
            q.clearPrimary(at.millis(), key.value)
            q.markPrimary(at.millis(), key.value)
        }
    }

    override suspend fun roots(category: CategoryKey): List<CategoryRoot> =
        withContext(io) { q.selectRoots(category.value).executeAsList().map { it.toDomain() } }

    override suspend fun upsertRoot(root: CategoryRoot) = withContext(io) {
        val row = root.toRow()
        q.transaction {
            q.insertOrIgnoreRoot(row)
            q.updateRoot(row.category_key, row.location, row.sort_order, row.key)
        }
    }

    override suspend fun deleteRoot(key: RootKey) = withContext(io) { q.deleteRoot(key.value); Unit }
}

class SqlLibraryRepository(private val db: RachaDatabase, private val io: CoroutineContext) : LibraryRepository {
    private val q get() = db.libraryQueries

    override fun observeItems(category: CategoryKey): Flow<List<LibraryItem>> =
        q.selectItemsByCategory(category.value).asFlow().mapToList(io).map { rows -> rows.map { it.toDomain() } }

    override suspend fun item(id: ContentId): LibraryItem? =
        withContext(io) { q.selectItem(id.value).executeAsOneOrNull()?.toDomain() }

    override suspend fun upsertItem(item: LibraryItem) = withContext(io) {
        val r = item.toRow()
        q.transaction {
            q.insertOrIgnoreItem(r)
            q.updateItem(
                r.category_key, r.root_key, r.relative_path, r.kind, r.title, r.title_override, r.description,
                r.poster_path, r.sort_order, r.order_override, r.total_items, r.total_duration_sec, r.progress_pct,
                r.archived, r.first_seen_at, r.updated_at, r.id,
            )
        }
    }

    override suspend fun sections(item: ContentId): List<Section> =
        withContext(io) { q.selectSections(item.value).executeAsList().map { it.toDomain() } }

    override suspend fun upsertSection(section: Section) = withContext(io) {
        val r = section.toRow()
        q.transaction {
            q.insertOrIgnoreSection(r)
            q.updateSection(r.library_item_id, r.kind, r.title, r.title_override, r.sort_order, r.order_override, r.updated_at, r.id)
        }
    }

    override fun observeElements(item: ContentId): Flow<List<PlayableElement>> =
        q.selectElementsByItem(item.value).asFlow().mapToList(io).map { rows -> rows.map { it.toDomain() } }

    override suspend fun elements(item: ContentId): List<PlayableElement> =
        withContext(io) { q.selectElementsByItem(item.value).executeAsList().map { it.toDomain() } }

    override suspend fun element(id: ContentId): PlayableElement? =
        withContext(io) { q.selectElement(id.value).executeAsOneOrNull()?.toDomain() }

    override suspend fun upsertElement(element: PlayableElement) = withContext(io) {
        val r = element.toRow()
        q.transaction {
            q.insertOrIgnoreElement(r)
            q.updateElement(
                r.library_item_id, r.section_id, r.kind, r.resource_type, r.title, r.title_override, r.relative_path,
                r.duration_sec, r.width, r.height, r.video_codec, r.fps, r.bitrate_kbps, r.sort_order, r.order_override,
                r.archived, r.file_size, r.file_modified_at, r.file_hash, r.first_seen_at, r.updated_at, r.id,
            )
        }
    }

    override suspend fun elementsWithHash(fileHash: String): List<PlayableElement> =
        withContext(io) { q.selectElementsByHash(fileHash).executeAsList().map { it.toDomain() } }

    override suspend fun itemsInRoot(category: CategoryKey, root: RootKey): List<LibraryItem> =
        withContext(io) { q.selectItemsByRoot(category.value, root.value).executeAsList().map { it.toDomain() } }

    override suspend fun elementsInRoot(category: CategoryKey, root: RootKey): List<PlayableElement> =
        withContext(io) { q.selectElementsByRoot(category.value, root.value).executeAsList().map { it.toDomain() } }

    override suspend fun archivedElementsWithSize(fileSize: Long): List<PlayableElement> =
        withContext(io) { q.selectArchivedWithSize(fileSize, ::Playable_element).executeAsList().map { it.toDomain() } }

    override suspend fun elementsNeedingHash(limit: Int): List<PlayableElement> =
        withContext(io) { q.selectNeedingHash(limit.toLong()).executeAsList().map { it.toDomain() } }

    override suspend fun relinkElement(from: ContentId, to: ContentId) = withContext(io) {
        q.transaction {
            q.relinkProgress(to.value, from.value)
            q.deleteProgress(from.value) // only left over if [to] already had its own progress
            q.relinkBookmarks(to.value, from.value)
            q.relinkHistory(to.value, from.value)
            q.relinkFavorite(to.value, from.value)
            q.deleteFavoriteElement(from.value)
            q.deleteElement(from.value)
        }
    }

    override suspend fun subtitles(element: ContentId): List<Subtitle> =
        withContext(io) { q.selectSubtitles(element.value).executeAsList().map { it.toDomain() } }

    override suspend fun upsertSubtitle(subtitle: Subtitle) = withContext(io) {
        val r = subtitle.toRow()
        q.transaction {
            q.insertOrIgnoreSubtitle(r)
            q.updateSubtitle(r.element_id, r.language, r.relative_path, r.updated_at, r.id)
        }
    }

    override suspend fun deleteSubtitle(id: ContentId) = withContext(io) { q.deleteSubtitle(id.value); Unit }
}
