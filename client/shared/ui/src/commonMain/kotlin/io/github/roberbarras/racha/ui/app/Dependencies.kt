package io.github.roberbarras.racha.ui.app

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
)

/** Platform capabilities the shared UI needs. */
interface PlatformHooks {
    /** Lets the user pick a folder; returns its `file://` location or null if cancelled. */
    suspend fun pickFolder(): String?

    /** Whether the app can read arbitrary folders (Android "All files access"); always true on desktop. */
    val storageAccess: Flow<Boolean> get() = flowOf(true)

    /** Opens the system screen that grants storage access. */
    fun requestStorageAccess() {}
}
