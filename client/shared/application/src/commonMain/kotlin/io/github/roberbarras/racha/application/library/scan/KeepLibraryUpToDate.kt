package io.github.roberbarras.racha.application.library.scan

import io.github.roberbarras.racha.application.library.CategoryRepository
import io.github.roberbarras.racha.application.preferences.Preferences
import io.github.roberbarras.racha.domain.library.CategoryKey
import io.github.roberbarras.racha.domain.preferences.Settings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Keeps the index current (spec §3.3): scans on start when automatic scanning is on, then rescans a category when
 * its folders change (if the file watcher is on). Bursts of changes (copying a course = hundreds of events) are
 * coalesced with [quietPeriod]. Background jobs (hashes, posters) follow each scan. Runs until cancelled; platforms
 * run it while the app is in use, so no watcher stays alive in the background.
 */
/** Observable scan state for the UI ("Library up to date · Scan", spec §10.3 "sincronizando/cargando" states). */
data class ScanStatus(val running: Boolean = false, val last: ScanReport? = null)

class KeepLibraryUpToDate(
    private val categories: CategoryRepository,
    private val scan: ScanLibrary,
    private val hashEngaged: HashEngagedElements,
    private val refreshThumbnails: RefreshThumbnails,
    private val watcher: LibraryWatcher,
    private val preferences: Preferences,
    private val quietPeriod: Duration = 3.seconds,
) {
    private val mutableStatus = MutableStateFlow(ScanStatus())
    val status: StateFlow<ScanStatus> = mutableStatus.asStateFlow()

    private suspend fun <T : ScanReport> tracked(block: suspend () -> T): T {
        mutableStatus.update { it.copy(running = true) }
        try {
            return block().also { report -> mutableStatus.update { ScanStatus(running = false, last = report) } }
        } finally {
            mutableStatus.update { it.copy(running = false) }
        }
    }

    /** Manual "Scan now" from settings (spec §3.3), regardless of the automatic setting. */
    suspend fun scanNow(): ScanReport = tracked { scan.all() }.also { afterScan() }

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    suspend fun run() {
        if (preferences.get(Settings.AutoScan)) scanNow()
        val changes: Flow<CategoryKey> =
            combine(preferences.observe(Settings.FileWatcher), categories.observeAll()) { enabled, all ->
                if (!enabled) return@combine emptyFlow()
                all.flatMap { category -> categories.roots(category.key).map { root -> watcher.changes(root.location).map { category.key } } }.merge()
            }.flatMapLatest { it }
        // Every category touched during the quiet period is rescanned, not only the last one.
        val pending = mutableSetOf<CategoryKey>()
        val lock = Mutex()
        changes
            .onEach { key -> lock.withLock { pending += key } }
            .debounce(quietPeriod)
            .collect {
                val keys = lock.withLock { pending.toSet().also { pending.clear() } }
                tracked { keys.fold(ScanReport()) { report, key -> report + scan.category(key) } }
                afterScan()
            }
    }

    private suspend fun afterScan() {
        hashEngaged()
        refreshThumbnails()
    }
}
