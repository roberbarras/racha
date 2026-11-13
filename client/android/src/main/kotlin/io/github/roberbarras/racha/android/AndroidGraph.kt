package io.github.roberbarras.racha.android

import android.content.Context
import io.github.roberbarras.racha.android.library.AndroidMediaProbe
import io.github.roberbarras.racha.android.library.AndroidThumbnailRenderer
import io.github.roberbarras.racha.android.library.FileObserverLibraryWatcher
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
import io.github.roberbarras.racha.application.browse.UpdateItemProgress
import io.github.roberbarras.racha.application.library.EnsureBuiltInCategories
import io.github.roberbarras.racha.application.library.scan.HashEngagedElements
import io.github.roberbarras.racha.application.library.scan.KeepLibraryUpToDate
import io.github.roberbarras.racha.application.library.scan.RefreshThumbnails
import io.github.roberbarras.racha.application.library.scan.ScanLibrary
import io.github.roberbarras.racha.application.preferences.Preferences
import io.github.roberbarras.racha.application.shared.Clock
import io.github.roberbarras.racha.files.NioLibrarySource
import io.github.roberbarras.racha.files.Sha256FileHasher
import io.github.roberbarras.racha.files.SystemLocalDates
import io.github.roberbarras.racha.ui.app.PlatformHooks
import io.github.roberbarras.racha.ui.app.RachaDependencies
import java.io.File

/** Android composition root: one per process (held by [RachaApplication]), wired by hand. */
class AndroidGraph(context: Context) {
    val database = AndroidDatabase.create(context)
    val clock = Clock.SYSTEM
    private val localDates = SystemLocalDates()
    val preferences = Preferences(database.settings, clock)
    private val hasher = Sha256FileHasher()

    val ensureBuiltInCategories = EnsureBuiltInCategories(database.categories, clock)
    val scanLibrary = ScanLibrary(
        database.categories, database.library, database.progress, NioLibrarySource(), AndroidMediaProbe(), hasher, preferences, clock,
        localDates,
    )
    val keepLibraryUpToDate = KeepLibraryUpToDate(
        database.categories,
        scanLibrary,
        HashEngagedElements(database.categories, database.library, hasher),
        RefreshThumbnails(database.categories, database.library, AndroidThumbnailRenderer(File(context.cacheDir, "thumbs")), preferences),
        FileObserverLibraryWatcher(),
        preferences,
    )

    private val updateItemProgress = UpdateItemProgress(database.library, database.progress)

    /** UI dependencies for one activity (the platform hooks are bound to it). */
    fun dependencies(platform: PlatformHooks) = RachaDependencies(
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
    )
}
