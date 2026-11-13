package io.github.roberbarras.racha.application.library.scan

import io.github.roberbarras.racha.application.library.CategoryRepository
import io.github.roberbarras.racha.application.preferences.Preferences
import io.github.roberbarras.racha.domain.library.CategoryKey
import io.github.roberbarras.racha.domain.preferences.Settings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
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
class KeepLibraryUpToDate(
    private val categories: CategoryRepository,
    private val scan: ScanLibrary,
    private val hashEngaged: HashEngagedElements,
    private val refreshThumbnails: RefreshThumbnails,
    private val watcher: LibraryWatcher,
    private val preferences: Preferences,
    private val quietPeriod: Duration = 3.seconds,
) {
    /** Manual "Scan now" from settings (spec §3.3), regardless of the automatic setting. */
    suspend fun scanNow(): ScanReport = scan.all().also { afterScan() }

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
                keys.forEach { scan.category(it) }
                afterScan()
            }
    }

    private suspend fun afterScan() {
        hashEngaged()
        refreshThumbnails()
    }
}
