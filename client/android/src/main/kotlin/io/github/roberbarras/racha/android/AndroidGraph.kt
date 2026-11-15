package io.github.roberbarras.racha.android

import io.github.roberbarras.racha.application.resource.ResourceViewer
import io.github.roberbarras.racha.files.NioResourceFiles
import android.content.Context
import io.github.roberbarras.racha.android.library.AndroidMediaProbe
import io.github.roberbarras.racha.android.library.AndroidThumbnailRenderer
import io.github.roberbarras.racha.android.library.FileObserverLibraryWatcher
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
import io.github.roberbarras.racha.files.NioLibrarySource
import io.github.roberbarras.racha.files.Sha256FileHasher
import io.github.roberbarras.racha.files.SystemLocalDates
import io.github.roberbarras.racha.ui.app.PlatformHooks
import io.github.roberbarras.racha.ui.app.RachaDependencies
import java.io.File
import android.os.Build
import io.github.roberbarras.racha.android.sync.ConnectivityMonitor
import io.github.roberbarras.racha.android.sync.KeystoreCredentialStore
import io.github.roberbarras.racha.android.sync.SyncWorker
import io.github.roberbarras.racha.application.sync.AccountService
import io.github.roberbarras.racha.application.sync.DeviceDescriptor
import io.github.roberbarras.racha.application.sync.SyncControls
import io.github.roberbarras.racha.application.sync.SyncEngine
import io.github.roberbarras.racha.application.sync.SyncScheduler
import io.github.roberbarras.racha.domain.preferences.Settings
import io.github.roberbarras.racha.remote.KtorSyncServer
import io.github.roberbarras.racha.application.stats.LoadPersonalStats
import io.github.roberbarras.racha.application.stats.RemoteStatsSource
import io.github.roberbarras.racha.application.telemetry.Telemetry
import io.github.roberbarras.racha.application.telemetry.TelemetryUploader
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Sync (§7.4, opt-in): in the foreground while the app is visible ([MainActivity]), otherwise via WorkManager.
    private val syncServer = KtorSyncServer()
    val account = AccountService(
        syncServer, KeystoreCredentialStore(context), database.sync, database.accounts, preferences,
        { DeviceDescriptor(null, deviceName(context), "android", appVersion(context)) }, clock,
    )
    /** Usage events and client errors for the account's server (§8); nothing is kept while signed out. */
    val telemetry = Telemetry(database.telemetry, preferences, clock, "android", appVersion(context))
    val telemetryUploader = TelemetryUploader(database.telemetry, syncServer, account, preferences, clock)
    val syncEngine = SyncEngine(database.sync, syncServer, account, preferences, clock, telemetry)
    val syncScheduler = SyncScheduler(syncEngine, preferences) { telemetryUploader.upload() }
    val connectivity = ConnectivityMonitor(context)
    private val syncControls = SyncControls(account, syncEngine, syncScheduler, database.sync)

    init {
        val app = context.applicationContext
        appScope.launch {
            account.restore()
            telemetry.appOpened()
            combine(preferences.observe(Settings.SyncEnabled), preferences.observe(Settings.SyncIntervalMinutes)) { on, minutes -> on to minutes }
                .collect { (on, minutes) -> SyncWorker.schedule(app, on, minutes) }
        }
    }

    /** The app went to the background: anything still queued goes out with the next network. */
    fun flushSyncInBackground(context: Context) {
        appScope.launch {
            if (preferences.get(Settings.SyncEnabled) && database.sync.observe().first().pending > 0) SyncWorker.flushSoon(context)
        }
    }

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
        manageRoots = ManageRoots(
            database.categories, database.library, scanLibrary, clock, { database.sync.remoteRoots(it.value) }, database.rootRelinker,
        ),
        setMode = SetMode(preferences),
        keepLibraryUpToDate = keepLibraryUpToDate,
        platform = platform,
        resourceViewer = {
            ResourceViewer(database.library, database.categories, NioResourceFiles(), database.progress, database.history, updateItemProgress, clock, telemetry)
        },
        playbackController = { engine, scope ->
        PlaybackController(
            engine, scope, database.library, database.categories, NioLibrarySource(), database.progress, database.bookmarks,
            database.history, database.stats, preferences, updateItemProgress, clock, localDates, telemetry,
        ) { platform.player?.language() ?: "en" }
    },
        appScope = appScope,
        sync = syncControls,
        loadPersonalStats = LoadPersonalStats(
            database.categories, database.library, database.progress, database.history, database.stats, localDates, clock,
            RemoteStatsSource(syncServer, account, preferences) { java.util.TimeZone.getDefault().id },
        ),
    )

    private companion object {
        fun deviceName(context: Context): String =
            android.provider.Settings.Global.getString(context.contentResolver, android.provider.Settings.Global.DEVICE_NAME)
                ?.takeIf { it.isNotBlank() } ?: "${Build.MANUFACTURER} ${Build.MODEL}"

        fun appVersion(context: Context): String =
            runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "dev"
    }
}
