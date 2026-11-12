package io.github.roberbarras.racha.application.learning

import io.github.roberbarras.racha.domain.learning.Bookmark
import io.github.roberbarras.racha.domain.learning.Favorite
import io.github.roberbarras.racha.domain.learning.FavoriteTarget
import io.github.roberbarras.racha.domain.learning.HistoryEntry
import io.github.roberbarras.racha.domain.learning.LocalStat
import io.github.roberbarras.racha.domain.learning.Progress
import io.github.roberbarras.racha.domain.library.ContentId
import io.github.roberbarras.racha.domain.library.LibraryItem
import io.github.roberbarras.racha.domain.library.PlayableElement
import kotlinx.coroutines.flow.Flow

/** Read model of the Home "Continue watching" row: unfinished elements, most recently watched first. */
data class ContinueWatchingEntry(val item: LibraryItem, val element: PlayableElement, val progress: Progress)

interface ProgressRepository {
    suspend fun get(element: ContentId): Progress?

    suspend fun save(progress: Progress)

    suspend fun forItem(item: ContentId): List<Progress>

    /** Emits again whenever progress changes, so the row updates in real time (spec §4.3). */
    fun observeContinueWatching(limit: Int): Flow<List<ContinueWatchingEntry>>
}

interface BookmarkRepository {
    fun observe(element: ContentId): Flow<List<Bookmark>>

    suspend fun save(bookmark: Bookmark)

    suspend fun delete(id: String)
}

interface HistoryRepository {
    suspend fun add(entry: HistoryEntry)

    fun observeRecent(limit: Int): Flow<List<HistoryEntry>>

    suspend fun clear()
}

interface FavoriteRepository {
    fun observeAll(): Flow<List<Favorite>>

    suspend fun add(favorite: Favorite)

    suspend fun remove(target: FavoriteTarget)
}

interface LocalStatRepository {
    suspend fun get(metric: String, period: String): LocalStat?

    suspend fun save(stat: LocalStat)

    suspend fun forMetric(metric: String): List<LocalStat>
}
