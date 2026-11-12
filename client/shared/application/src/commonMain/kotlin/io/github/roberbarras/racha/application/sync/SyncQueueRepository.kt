package io.github.roberbarras.racha.application.sync

import io.github.roberbarras.racha.domain.sync.SyncOperation
import io.github.roberbarras.racha.domain.sync.SyncQueueEntry
import kotlin.time.Instant

/** Outbound port: local changes waiting to be pushed (spec §7.4). */
interface SyncQueueRepository {
    suspend fun enqueue(entityType: String, entityId: String, operation: SyncOperation, payload: String?, at: Instant)

    /** Oldest first. */
    suspend fun pending(limit: Int): List<SyncQueueEntry>

    suspend fun markFailed(id: Long, error: String)

    suspend fun remove(ids: Collection<Long>)

    suspend fun count(): Long
}
