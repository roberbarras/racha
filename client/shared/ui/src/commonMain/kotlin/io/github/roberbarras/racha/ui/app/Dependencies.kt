package io.github.roberbarras.racha.ui.app

import io.github.roberbarras.racha.application.resource.ResourceViewer
import io.github.roberbarras.racha.ui.resource.ResourceHooks
import io.github.roberbarras.racha.application.browse.GetElement
import io.github.roberbarras.racha.application.browse.ManageRoots
import io.github.roberbarras.racha.application.browse.MarkProgress
import io.github.roberbarras.racha.application.browse.ObserveCategory
import io.github.roberbarras.racha.application.browse.ObserveHistory
import io.github.roberbarras.racha.application.browse.ObserveHome
import io.github.roberbarras.racha.application.browse.ObserveItemDetail
import io.github.roberbarras.racha.application.browse.ObserveMyList
import io.github.roberbarras.racha.application.browse.SearchLibrary
import io.github.roberbarras.racha.application.browse.SetMode
import io.github.roberbarras.racha.application.browse.ToggleFavorite
import io.github.roberbarras.racha.application.library.CategoryRepository
import io.github.roberbarras.racha.application.library.scan.KeepLibraryUpToDate
import io.github.roberbarras.racha.application.library.scan.LocalDates
import io.github.roberbarras.racha.application.preferences.Preferences
import io.github.roberbarras.racha.application.shared.Clock
import io.github.roberbarras.racha.application.sync.SyncControls
import io.github.roberbarras.racha.application.stats.LoadPersonalStats
import io.github.roberbarras.racha.application.browse.ManageCategories
import io.github.roberbarras.racha.application.library.StorageMaintenance
import io.github.roberbarras.racha.application.preferences.ConfigTransfer
import io.github.roberbarras.racha.application.playback.PlaybackController
import io.github.roberbarras.racha.application.playback.PlayerEngine
import io.github.roberbarras.racha.ui.player.PlayerHooks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Use cases the UI drives, wired by each platform's composition root (the UI never sees adapters). */
class RachaDependencies(
    val clock: Clock,
    val localDates: LocalDates,
    val preferences: Preferences,
    val categories: CategoryRepository,
    val observeHome: ObserveHome,
    val observeCategory: ObserveCategory,
    val observeItemDetail: ObserveItemDetail,
    val observeHistory: ObserveHistory,
    val observeMyList: ObserveMyList,
    val searchLibrary: SearchLibrary,
    val markProgress: MarkProgress,
    val getElement: GetElement,
    val toggleFavorite: ToggleFavorite,
    val manageRoots: ManageRoots,
    val setMode: SetMode,
    val keepLibraryUpToDate: KeepLibraryUpToDate,
    val platform: PlatformHooks,
    /** Builds the shared playback controller around a platform engine (player screen). */
    val playbackController: (PlayerEngine, CoroutineScope) -> PlaybackController,
    /** A fresh resource viewer per screen (spec §5). */
    val resourceViewer: () -> ResourceViewer,
    /** Outlives screens: finishes saves when a player closes. */
    val appScope: CoroutineScope,
    /** Account and sync (§7.4); null where the platform does not offer it (demo, previews). */
    val sync: SyncControls? = null,
    /** Personal statistics (§2.2). */
    val loadPersonalStats: LoadPersonalStats,
    /** Settings (§9). */
    val manageCategories: ManageCategories,
    val storage: StorageMaintenance,
    val configTransfer: ConfigTransfer,
)

/** Platform capabilities the shared UI needs. */
interface PlatformHooks {
    /** Lets the user pick a folder; returns its `file://` location or null if cancelled. */
    suspend fun pickFolder(): String?

    /** Whether the app can read arbitrary folders (Android "All files access"); always true on desktop. */
    val storageAccess: Flow<Boolean> get() = flowOf(true)

    /** Opens the system screen that grants storage access. */
    fun requestStorageAccess() {}

    /** Video playback capabilities; null where the player is not available yet. */
    val player: PlayerHooks? get() = null

    /** Embedded web view, PDF rendering and "open outside" for resources; null where not available. */
    val resources: ResourceHooks? get() = null

    /** Shown in Settings > About. */
    val appVersion: String get() = "dev"

    /** Linux: the scale the desktop announces ("automatic" in Settings). Null where there is no scale selector. */
    val detectedUiScale: Float? get() = null

    /** Whether the player offers a hardware-decoding switch (Linux; Android always decodes in hardware). */
    val hardwareDecodingSwitch: Boolean get() = false

    /** Lets the user save [text] as a file; false if cancelled or it could not be written. */
    suspend fun saveTextFile(suggestedName: String, text: String): Boolean = false

    /** Lets the user pick a text file (configuration import); null if cancelled. */
    suspend fun openTextFile(): String? = null

    fun openUrl(url: String) {
        resources?.openExternal(url)
    }
}
