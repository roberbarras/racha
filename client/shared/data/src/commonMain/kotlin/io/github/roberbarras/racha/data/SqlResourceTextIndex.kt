package io.github.roberbarras.racha.data

import io.github.roberbarras.racha.application.library.search.ResourceTextIndex
import io.github.roberbarras.racha.application.library.search.TextHit
import io.github.roberbarras.racha.data.db.RachaDatabase
import io.github.roberbarras.racha.domain.library.ContentId
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.time.Instant

class SqlResourceTextIndex(private val database: RachaDatabase, private val io: CoroutineContext) : ResourceTextIndex {
    private val q get() = database.resourceTextQueries

    override suspend fun indexedVersion(element: ContentId): Instant? = withContext(io) {
        q.resourceTextVersion(element.value).executeAsOneOrNull()?.let(Instant::fromEpochMilliseconds)
    }

    override suspend fun put(element: ContentId, text: String, folded: String, fileModifiedAt: Instant) = withContext(io) {
        q.putResourceText(element.value, text, folded, fileModifiedAt.toEpochMilliseconds())
        Unit
    }

    override suspend fun search(foldedQuery: String, limit: Int): List<TextHit> = withContext(io) {
        val pattern = "%" + foldedQuery.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        q.searchResourceText(pattern, limit.toLong()).executeAsList().map { TextHit(ContentId(it.element_id), it.text, it.folded) }
    }
}
