package io.github.roberbarras.racha.data

import io.github.roberbarras.racha.application.library.RootRelinker
import io.github.roberbarras.racha.data.db.RachaDatabase
import io.github.roberbarras.racha.domain.library.CategoryRoot
import io.github.roberbarras.racha.domain.library.ContentId
import io.github.roberbarras.racha.domain.library.RelativePath
import io.github.roberbarras.racha.domain.library.RootKey
import kotlinx.coroutines.withContext
import io.github.roberbarras.racha.data.db.Category_root as CategoryRootRow
import kotlin.coroutines.CoroutineContext

/** [RootRelinker] in one SQLite transaction: re-key user data, drop the old library rows, swap the root's key. */
class SqlRootRelinker(private val db: RachaDatabase, private val io: CoroutineContext) : RootRelinker {
    override suspend fun relink(root: CategoryRoot, to: RootKey) = withContext(io) {
        if (root.key == to) return@withContext
        val category = root.categoryKey
        val lib = db.libraryQueries
        val learning = db.learningQueries
        db.transaction {
            for (element in lib.selectElementsByRoot(category.value, root.key.value).executeAsList()) {
                val path = RelativePath.of(element.relative_path)
                // Ids are reproducible from the path (ADR-0004): find which form this element used, then build it with the new key.
                val newId = when (element.id) {
                    ContentId.forVideo(category, root.key, path).value -> ContentId.forVideo(category, to, path).value
                    ContentId.forResource(category, root.key, path).value -> ContentId.forResource(category, to, path).value
                    else -> continue
                }
                val mine = learning.selectProgress(element.id).executeAsOneOrNull()
                val theirs = learning.selectProgress(newId).executeAsOneOrNull()
                if (mine != null && theirs != null) {
                    // Both devices watched it: the newer state wins, the furthest point reached is kept.
                    val newer = if (mine.updated_at > theirs.updated_at) mine else theirs
                    learning.updateProgress(newer.position_sec, newer.duration_sec, newer.completed,
                        maxOf(mine.max_position_sec, theirs.max_position_sec), newer.last_watched_at, newer.updated_at, newId)
                    lib.deleteProgress(element.id)
                } else {
                    lib.relinkProgress(newId, element.id)
                }
                lib.relinkBookmarks(newId, element.id)
                lib.relinkHistory(newId, element.id)
                lib.relinkFavorite(newId, element.id)
                learning.deleteFavorite("element", element.id)
            }
            for (item in lib.selectItemsByRoot(category.value, root.key.value).executeAsList()) {
                val newId = ContentId.forFolder(category, to, RelativePath.of(item.relative_path)).value
                db.syncQueries.relinkItemFavorite(newId, item.id)
                learning.deleteFavorite("item", item.id)
            }
            db.syncQueries.deleteItemsOfRoot(category.value, root.key.value)
            db.categoryQueries.deleteRoot(root.key.value)
            db.categoryQueries.insertOrIgnoreRoot(CategoryRootRow(to.value, category.value, root.location, root.order.toLong()))
            db.syncQueries.deleteRemoteRoot(to.value)
        }
    }
}
