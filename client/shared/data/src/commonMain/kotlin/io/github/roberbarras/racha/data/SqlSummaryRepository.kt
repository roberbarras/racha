package io.github.roberbarras.racha.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import io.github.roberbarras.racha.application.ai.SummaryRepository
import io.github.roberbarras.racha.data.db.RachaDatabase
import io.github.roberbarras.racha.domain.ai.Summary
import io.github.roberbarras.racha.domain.ai.SummaryTarget
import io.github.roberbarras.racha.domain.library.ContentId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.time.Instant
import io.github.roberbarras.racha.data.db.Summary as SummaryRow

/** AI summaries in SQLite (`summary`, v9). */
class SqlSummaryRepository(private val db: RachaDatabase, private val io: CoroutineContext) : SummaryRepository {
    private val q get() = db.summaryQueries

    override suspend fun get(target: ContentId): Summary? = withContext(io) { q.selectSummary(target.value).executeAsOneOrNull()?.toDomain() }

    override fun observe(target: ContentId): Flow<Summary?> = q.selectSummary(target.value) { id, type, md, provider, model, language, at ->
        SummaryRow(id, type, md, provider, model, language, at).toDomain()
    }.asFlow().mapToOneOrNull(io)

    override suspend fun save(summary: Summary) = withContext(io) {
        q.upsertSummary(SummaryRow(summary.targetId.value, summary.target.name, summary.markdown, summary.provider, summary.model,
            summary.language, summary.updatedAt.toEpochMilliseconds()))
        Unit
    }

    override suspend fun delete(target: ContentId) = withContext(io) { q.deleteSummary(target.value); Unit }

    /** Which of [targets] already have a summary (marks in the course screen). */
    suspend fun summarized(targets: Collection<ContentId>): Set<ContentId> = withContext(io) {
        targets.map { it.value }.chunked(500).flatMap { q.summarizedAmong(it).executeAsList() }.map(::ContentId).toSet()
    }

    private fun SummaryRow.toDomain() = Summary(
        ContentId(target_id), SummaryTarget.entries.firstOrNull { it.name == target_type } ?: SummaryTarget.ELEMENT, markdown, provider,
        model, language, Instant.fromEpochMilliseconds(updated_at),
    )
}
