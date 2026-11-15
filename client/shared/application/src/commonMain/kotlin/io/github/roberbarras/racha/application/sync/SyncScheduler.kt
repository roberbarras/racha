package io.github.roberbarras.racha.application.sync

import io.github.roberbarras.racha.application.preferences.Preferences
import io.github.roberbarras.racha.domain.preferences.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlin.time.Duration.Companion.minutes

/**
 * When to sync (spec §7.4): right away, then every N minutes (default 5) and whenever the network comes back or
 * something asks for it (sign-in, "sync now"). Runs only while [run] is active: platforms start it while the app is
 * in use, so an idle device does not wake up for it.
 */
class SyncScheduler(private val engine: SyncEngine, private val preferences: Preferences) {
    private val requests = Channel<Unit>(Channel.CONFLATED)

    fun requestSync() {
        requests.trySend(Unit)
    }

    /** Blocks (suspends) until cancelled. [online] emits connectivity; a rising edge triggers a sync. */
    suspend fun run(scope: CoroutineScope, online: Flow<Boolean>): Nothing {
        val watcher: Job = scope.launch {
            online.distinctUntilChanged().filter { it }.collect { requestSync() }
        }
        try {
            while (true) {
                if (preferences.get(Settings.SyncEnabled)) engine.syncNow()
                val interval = preferences.get(Settings.SyncIntervalMinutes).minutes
                select {
                    requests.onReceive { }
                    onTimeout(interval) { }
                }
                delay(DEBOUNCE_MS) // coalesce bursts (a sign-in plus a network change)
            }
        } finally {
            watcher.cancel()
        }
    }

    private companion object {
        const val DEBOUNCE_MS = 500L
    }
}
