package io.github.roberbarras.racha.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import io.github.roberbarras.racha.application.learning.BookmarkRepository
import io.github.roberbarras.racha.application.learning.ContinueWatchingEntry
import io.github.roberbarras.racha.application.learning.FavoriteRepository
import io.github.roberbarras.racha.application.learning.HistoryRepository
import io.github.roberbarras.racha.application.learning.LocalStatRepository
import io.github.roberbarras.racha.application.learning.ProgressRepository
import io.github.roberbarras.racha.data.db.RachaDatabase
import io.github.roberbarras.racha.domain.learning.Bookmark
import io.github.roberbarras.racha.domain.learning.Favorite
import io.github.roberbarras.racha.domain.learning.FavoriteTarget
import io.github.roberbarras.racha.domain.learning.HistoryEntry
import io.github.roberbarras.racha.domain.learning.LocalStat
import io.github.roberbarras.racha.domain.learning.Progress
import io.github.roberbarras.racha.domain.library.ContentId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import io.github.roberbarras.racha.data.db.Favorite as FavoriteRow

class SqlProgressRepository(private val db: RachaDatabase, private val io: CoroutineContext) : ProgressRepository {
    private val q get() = db.learningQueries

    override suspend fun get(element: ContentId): Progress? =
        withContext(io) { q.selectProgress(element.value).executeAsOneOrNull()?.toDomain() }

    override suspend fun save(progress: Progress) = withContext(io) {
        val r = progress.toRow()
        q.transaction {
            q.insertOrIgnoreProgress(r)
            q.updateProgress(r.position_sec, r.duration_sec, r.completed, r.max_position_sec, r.last_watched_at, r.updated_at, r.element_id)
        }
    }

    override suspend fun forItem(item: ContentId): List<Progress> =
        withContext(io) { q.selectProgressByItem(item.value).executeAsList().map { it.toDomain() } }

    override fun observeForItem(item: ContentId): Flow<List<Progress>> =
        q.selectProgressByItem(item.value).asFlow().mapToList(io).map { rows -> rows.map { it.toDomain() } }

    /** Re-queried whenever progress, elements or items change (SQLDelight table notifications). */
    override fun observeContinueWatching(limit: Int): Flow<List<ContinueWatchingEntry>> =
        q.selectContinueWatchingIds(limit.toLong()).asFlow().mapToList(io).map { ids ->
            withContext(io) {
                db.transactionWithResult {
                    ids.mapNotNull { id ->
                        val progress = q.selectProgress(id).executeAsOneOrNull()?.toDomain() ?: return@mapNotNull null
                        val element = db.libraryQueries.selectElement(id).executeAsOneOrNull()?.toDomain() ?: return@mapNotNull null
                        val item = db.libraryQueries.selectItem(element.libraryItemId.value).executeAsOneOrNull()?.toDomain()
                            ?: return@mapNotNull null
                        ContinueWatchingEntry(item, element, progress)
                    }
                }
            }
        }
}

class SqlBookmarkRepository(private val db: RachaDatabase, private val io: CoroutineContext) : BookmarkRepository {
    private val q get() = db.learningQueries

    override fun observe(element: ContentId): Flow<List<Bookmark>> =
        q.selectBookmarks(element.value).asFlow().mapToList(io).map { rows -> rows.map { it.toDomain() } }

    override suspend fun save(bookmark: Bookmark) = withContext(io) {
        val r = bookmark.toRow()
        q.transaction {
            q.insertOrIgnoreBookmark(r)
            q.updateBookmark(r.element_id, r.timestamp_sec, r.note, r.created_at, r.updated_at, r.id)
        }
    }

    override suspend fun delete(id: String) = withContext(io) { q.deleteBookmark(id); Unit }
}

class SqlHistoryRepository(private val db: RachaDatabase, private val io: CoroutineContext) : HistoryRepository {
    private val q get() = db.learningQueries

    override suspend fun add(entry: HistoryEntry) = withContext(io) { q.insertHistory(entry.toRow()); Unit }

    override fun observeRecent(limit: Int): Flow<List<HistoryEntry>> =
        q.selectRecentHistory(limit.toLong()).asFlow().mapToList(io).map { rows -> rows.map { it.toDomain() } }

    override suspend fun clear() = withContext(io) { q.clearHistory(); Unit }
}

class SqlFavoriteRepository(private val db: RachaDatabase, private val io: CoroutineContext) : FavoriteRepository {
    private val q get() = db.learningQueries

    override fun observeAll(): Flow<List<Favorite>> = q.selectFavorites().asFlow().mapToList(io).map { rows ->
        rows.map { Favorite(target(it.target_type, it.target_id), it.created_at.instant()) }
    }

    override suspend fun add(favorite: Favorite) = withContext(io) {
        q.insertFavorite(FavoriteRow(type(favorite.target), favorite.target.id.value, favorite.createdAt.millis()))
        Unit
    }

    override suspend fun remove(target: FavoriteTarget) = withContext(io) { q.deleteFavorite(type(target), target.id.value); Unit }

    private fun type(target: FavoriteTarget) = when (target) {
        is FavoriteTarget.Item -> ITEM
        is FavoriteTarget.Element -> ELEMENT
    }

    private fun target(type: String, id: String): FavoriteTarget = when (type) {
        ITEM -> FavoriteTarget.Item(ContentId(id))
        ELEMENT -> FavoriteTarget.Element(ContentId(id))
        else -> error("Unknown favorite type $type")
    }

    private companion object {
        const val ITEM = "item"
        const val ELEMENT = "element"
    }
}

class SqlLocalStatRepository(private val db: RachaDatabase, private val io: CoroutineContext) : LocalStatRepository {
    private val q get() = db.learningQueries

    override suspend fun get(metric: String, period: String): LocalStat? =
        withContext(io) { q.selectStat(metric, period).executeAsOneOrNull()?.toDomain() }

    override suspend fun save(stat: LocalStat) = withContext(io) {
        val r = stat.toRow()
        q.transaction {
            q.insertOrIgnoreStat(r)
            q.updateStat(r.value_, r.updated_at, r.metric, r.period)
        }
    }

    override suspend fun forMetric(metric: String): List<LocalStat> =
        withContext(io) { q.selectStatsByMetric(metric).executeAsList().map { it.toDomain() } }
}
