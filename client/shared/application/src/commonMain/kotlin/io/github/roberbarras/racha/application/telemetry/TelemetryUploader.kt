package io.github.roberbarras.racha.application.telemetry

import io.github.roberbarras.racha.application.preferences.Preferences
import io.github.roberbarras.racha.application.shared.Clock
import io.github.roberbarras.racha.application.sync.AccountService
import io.github.roberbarras.racha.application.sync.RemoteError
import io.github.roberbarras.racha.application.sync.ServerAddress
import io.github.roberbarras.racha.domain.preferences.Settings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Sends queued telemetry in batches (§11 "en batch, reintentos con backoff") after each sync round. Failures wait
 * 1, 2, 4… up to 60 minutes before the next try; a batch the server refuses outright is dropped so it cannot block
 * the rest. Goes to the sync server, or to the telemetry endpoint when one is configured (§9 "Avanzado").
 */
class TelemetryUploader(
    private val outbox: TelemetryOutbox,
    private val server: TelemetryServer,
    private val account: AccountService,
    private val preferences: Preferences,
    private val clock: Clock,
) {
    private val lock = Mutex()
    private var failures = 0
    private var notBefore: Instant = Instant.DISTANT_PAST

    /** Number of entries sent. */
    suspend fun upload(): Int = lock.withLock {
        if (!preferences.get(Settings.SyncEnabled) || clock.now() < notBefore) return@withLock 0
        val baseUrl = baseUrl() ?: return@withLock 0
        var sent = 0
        try {
            while (true) {
                val batch = outbox.events(EVENT_BATCH).takeIf { it.isNotEmpty() } ?: break
                if (!send { server.sendEvents(baseUrl, it, batch) }) return@withLock sent
                outbox.remove(batch.map { it.id })
                sent += batch.size
            }
            while (true) {
                val batch = outbox.errors(ERROR_BATCH).takeIf { it.isNotEmpty() } ?: break
                if (!send { server.sendErrors(baseUrl, it, batch) }) return@withLock sent
                outbox.remove(batch.map { it.id })
                sent += batch.size
            }
            failures = 0
            notBefore = Instant.DISTANT_PAST
        } catch (e: RemoteError) {
            failures++
            notBefore = clock.now() + backoff(failures)
        }
        sent
    }

    /** Calls [block] with a token (refreshed once on 401); false when there is no session. A refused batch is dropped. */
    private suspend fun send(block: suspend (String) -> Unit): Boolean {
        val token = account.accessToken() ?: return false
        try {
            block(token)
        } catch (e: RemoteError.Unauthorized) {
            block(account.accessToken(forceRefresh = true) ?: return false)
        } catch (e: RemoteError.Rejected) {
            if (e.status !in 400..499 || e.status == 401 || e.status == 429) throw e // server trouble or too many: retry later
        }
        return true
    }

    private suspend fun baseUrl(): String? {
        val custom = preferences.get(Settings.TelemetryEndpoint).trim().trimEnd('/')
        if (custom.startsWith("https://") || custom.startsWith("http://")) return custom
        return ServerAddress.from(preferences)?.baseUrl
    }

    companion object {
        const val EVENT_BATCH = 500
        const val ERROR_BATCH = 100

        fun backoff(failures: Int): Duration = minOf(1.minutes * (1 shl (failures - 1).coerceIn(0, 6)), 60.minutes)
    }
}
