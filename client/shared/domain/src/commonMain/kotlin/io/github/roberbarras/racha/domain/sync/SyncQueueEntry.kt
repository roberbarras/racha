package io.github.roberbarras.racha.domain.sync

import kotlin.time.Instant

enum class SyncOperation { UPSERT, DELETE }

/** A local change waiting to be pushed to the server (spec §7.4). [payload] is the serialized entity. */
data class SyncQueueEntry(
    val id: Long,
    val entityType: String,
    val entityId: String,
    val operation: SyncOperation,
    val payload: String?,
    val retries: Int,
    val lastError: String?,
    val createdAt: Instant,
)
