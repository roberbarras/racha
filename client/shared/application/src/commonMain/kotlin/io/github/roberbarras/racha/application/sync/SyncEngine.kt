package io.github.roberbarras.racha.application.sync

import io.github.roberbarras.racha.application.preferences.Preferences
import io.github.roberbarras.racha.application.shared.Clock
import io.github.roberbarras.racha.domain.preferences.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class SyncRun { IDLE, SYNCING }

/** How a sync attempt ended. */
sealed interface SyncOutcome {
    data class Done(val pushed: Int, val pulled: Int) : SyncOutcome

    /** Sync is off, or there is no valid session. */
    data object NotSignedIn : SyncOutcome

    data class Failed(val error: RemoteError) : SyncOutcome
}

/**
 * One sync round (spec §7.4, ADR-0012): push the local outbox in batches, apply what the server says is newer,
 * then pull everything changed since the cursor. One round at a time; a failure leaves the queue for the next one.
 */
class SyncEngine(
    private val local: SyncLocalStore,
    private val server: SyncServer,
    private val account: AccountService,
    private val preferences: Preferences,
    private val clock: Clock,
) {
    private val running = MutableStateFlow(SyncRun.IDLE)
    val run: StateFlow<SyncRun> = running.asStateFlow()
    private val lock = Mutex()

    suspend fun syncNow(): SyncOutcome = lock.withLock {
        if (!preferences.get(Settings.SyncEnabled)) return@withLock SyncOutcome.NotSignedIn
        val address = ServerAddress.from(preferences) ?: return@withLock SyncOutcome.NotSignedIn
        running.value = SyncRun.SYNCING
        try {
            val pushed = authorized { token -> push(address, token) } ?: return@withLock SyncOutcome.NotSignedIn
            val pulled = authorized { token -> pull(address, token) } ?: return@withLock SyncOutcome.NotSignedIn
            local.recordSuccess(clock.now())
            SyncOutcome.Done(pushed, pulled)
        } catch (e: RemoteError) {
            local.recordError(e.message ?: e::class.simpleName.orEmpty())
            SyncOutcome.Failed(e)
        } finally {
            running.value = SyncRun.IDLE
        }
    }

    /** Runs [block] with an access token, refreshing it once if the server says it is no longer valid. */
    private suspend fun <T> authorized(block: suspend (String) -> T): T? {
        val token = account.accessToken() ?: return null
        return try {
            block(token)
        } catch (e: RemoteError.Unauthorized) {
            val fresh = account.accessToken(forceRefresh = true) ?: return null
            block(fresh)
        }
    }

    private suspend fun push(address: ServerAddress, token: String): Int {
        val policy = preferences.get(Settings.SyncConflictPolicy)
        var pushed = 0
        repeat(MAX_ROUNDS) {
            val batch = local.pending(BATCH)
            if (batch.isEmpty()) return pushed
            val results = server.push(address, token, batch.map { it.change })
            val byKey = results.associateBy { (it.entity to it.id) }
            val done = mutableListOf<PendingChange>()
            val serverNewer = mutableListOf<SyncRecord>()
            val retimed = mutableListOf<Pair<SyncChange, SyncRecord>>()
            for (pending in batch) {
                val result = byKey[pending.change.entity to pending.change.id]
                when (result?.outcome) {
                    PushOutcome.APPLIED -> {
                        done += pending
                        result.server?.takeIf { it.updatedAt != pending.change.updatedAt }?.let { retimed += pending.change to it }
                    }
                    PushOutcome.STALE -> {
                        done += pending
                        result.server?.let { serverNewer += it }
                    }
                    PushOutcome.INVALID -> local.fail(pending, result.reason ?: "invalid")
                    null -> local.fail(pending, "no result from the server")
                }
            }
            // The server kept a newer version: take it (settings may become a question for the user instead).
            if (serverNewer.isNotEmpty()) local.applyRemote(serverNewer, policy)
            if (retimed.isNotEmpty()) local.adoptServerTimes(retimed)
            local.acknowledge(done)
            pushed += done.size
            if (batch.size < BATCH) return pushed
        }
        return pushed
    }

    private suspend fun pull(address: ServerAddress, token: String): Int {
        val policy = preferences.get(Settings.SyncConflictPolicy)
        var cursor = local.cursor()
        var pulled = 0
        repeat(MAX_ROUNDS) {
            val page = server.pull(address, token, cursor, BATCH)
            if (page.records.isNotEmpty()) local.applyRemote(page.records, policy)
            cursor = page.cursor
            local.setCursor(cursor)
            pulled += page.records.size
            if (!page.hasMore) return pulled
        }
        return pulled
    }

    private companion object {
        const val BATCH = 500
        const val MAX_ROUNDS = 200
    }
}
