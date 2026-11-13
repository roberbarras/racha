package io.github.roberbarras.racha.desktop

import io.github.roberbarras.racha.application.browse.GetElement
import io.github.roberbarras.racha.application.playback.PlaybackController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
import io.github.roberbarras.racha.application.browse.UpdateItemProgress
import io.github.roberbarras.racha.application.library.EnsureBuiltInCategories
import io.github.roberbarras.racha.application.library.scan.HashEngagedElements
import io.github.roberbarras.racha.application.library.scan.KeepLibraryUpToDate
import io.github.roberbarras.racha.application.library.scan.RefreshThumbnails
import io.github.roberbarras.racha.application.library.scan.ScanLibrary
import io.github.roberbarras.racha.application.preferences.Preferences
import io.github.roberbarras.racha.application.shared.Clock
import io.github.roberbarras.racha.data.LocalDatabase
import io.github.roberbarras.racha.desktop.library.MpvMediaProbe
import io.github.roberbarras.racha.desktop.library.MpvThumbnailRenderer
import io.github.roberbarras.racha.desktop.library.WatchServiceLibraryWatcher
import io.github.roberbarras.racha.files.NioLibrarySource
import io.github.roberbarras.racha.files.Sha256FileHasher
import io.github.roberbarras.racha.files.SystemLocalDates
import io.github.roberbarras.racha.ui.app.PlatformHooks
import io.github.roberbarras.racha.ui.app.RachaDependencies

/** Desktop composition root: wires adapters into use cases by hand (no reflection, no DI container). */
class DesktopGraph(val database: LocalDatabase = DesktopDatabase.open(), platform: PlatformHooks = DesktopPlatform()) : AutoCloseable {
    val clock = Clock.SYSTEM
    private val localDates = SystemLocalDates()
    val preferences = Preferences(database.settings, clock)
    private val probe = MpvMediaProbe()
    private val hasher = Sha256FileHasher()

    val ensureBuiltInCategories = EnsureBuiltInCategories(database.categories, clock)
    val scanLibrary = ScanLibrary(
        database.categories, database.library, database.progress, NioLibrarySource(), probe, hasher, preferences, clock, localDates,
    )
    val keepLibraryUpToDate = KeepLibraryUpToDate(
        database.categories,
        scanLibrary,
        HashEngagedElements(database.categories, database.library, hasher),
        RefreshThumbnails(database.categories, database.library, MpvThumbnailRenderer(DesktopPaths.thumbnails), preferences),
        WatchServiceLibraryWatcher(),
        preferences,
    )

    private val updateItemProgress = UpdateItemProgress(database.library, database.progress)
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val dependencies = RachaDependencies(
        clock = clock,
        localDates = localDates,
        preferences = preferences,
        categories = database.categories,
        observeHome = ObserveHome(database.categories, database.library, database.progress, database.history, preferences, clock, localDates),
        observeCategory = ObserveCategory(database.categories, database.library),
        observeItemDetail = ObserveItemDetail(database.categories, database.library, database.progress, database.favorites),
        observeHistory = ObserveHistory(database.history, database.library),
        observeMyList = ObserveMyList(database.favorites, database.library),
        searchLibrary = SearchLibrary(database.library),
        markProgress = MarkProgress(database.library, database.progress, updateItemProgress, clock),
        getElement = GetElement(database.library, database.progress),
        toggleFavorite = ToggleFavorite(database.favorites, clock),
        manageRoots = ManageRoots(database.categories, database.library, scanLibrary, clock),
        setMode = SetMode(preferences),
        keepLibraryUpToDate = keepLibraryUpToDate,
        platform = platform,
        playbackController = { engine, scope ->
        PlaybackController(
            engine, scope, database.library, database.categories, NioLibrarySource(), database.progress, database.bookmarks,
            database.history, database.stats, preferences, updateItemProgress, clock, localDates,
        ) { platform.player?.language() ?: "en" }
    },
        appScope = appScope,
    )

    override fun close() = probe.close()
}
