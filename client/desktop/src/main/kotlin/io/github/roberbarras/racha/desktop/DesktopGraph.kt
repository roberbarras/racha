package io.github.roberbarras.racha.desktop

import io.github.roberbarras.racha.application.resource.ResourceViewer
import io.github.roberbarras.racha.files.NioResourceFiles
import io.github.roberbarras.racha.application.browse.GetElement
import io.github.roberbarras.racha.application.playback.PlaybackController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import io.github.roberbarras.racha.desktop.mpv.LibMpv
import io.github.roberbarras.racha.desktop.player.DesktopPlayerHooks
import io.github.roberbarras.racha.domain.preferences.Settings
import io.github.roberbarras.racha.application.browse.ManageRoots
import io.github.roberbarras.racha.application.browse.MarkProgress
import io.github.roberbarras.racha.application.browse.ObserveCategory
import io.github.roberbarras.racha.application.browse.ObserveHistory
import io.github.roberbarras.racha.application.browse.ObserveHome
import io.github.roberbarras.racha.application.browse.ObserveItemDetail
import io.github.roberbarras.racha.application.browse.ObserveMyList
import io.github.roberbarras.racha.application.browse.SearchLibrary
import io.github.roberbarras.racha.application.library.search.IndexResourceText
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
import io.github.roberbarras.racha.application.sync.AccountService
import io.github.roberbarras.racha.application.sync.DeviceDescriptor
import io.github.roberbarras.racha.application.sync.SyncControls
import io.github.roberbarras.racha.application.sync.SyncEngine
import io.github.roberbarras.racha.application.sync.SyncScheduler
import io.github.roberbarras.racha.desktop.sync.LibSecretCredentialStore
import io.github.roberbarras.racha.desktop.sync.NetworkMonitor
import io.github.roberbarras.racha.remote.KtorSyncServer
import io.github.roberbarras.racha.desktop.notifications.DesktopNotifier
import io.github.roberbarras.racha.application.notifications.NotifyNewContent
import io.github.roberbarras.racha.application.notifications.NotifySyncFailure
import io.github.roberbarras.racha.application.notifications.StudyReminder
import io.github.roberbarras.racha.files.ReminderTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import io.github.roberbarras.racha.remote.HttpOptions
import io.github.roberbarras.racha.application.browse.ManageCategories
import io.github.roberbarras.racha.application.library.StorageMaintenance
import io.github.roberbarras.racha.application.preferences.ConfigTransfer
import io.github.roberbarras.racha.application.shared.AppLog
import io.github.roberbarras.racha.files.DirectoryThumbnailCache
import io.github.roberbarras.racha.application.stats.LoadPersonalStats
import io.github.roberbarras.racha.application.stats.RemoteStatsSource
import io.github.roberbarras.racha.application.telemetry.Telemetry
import io.github.roberbarras.racha.application.telemetry.TelemetryUploader
import java.net.InetAddress

/** Desktop composition root: wires adapters into use cases by hand (no reflection, no DI container). */
class DesktopGraph(val database: LocalDatabase = DesktopDatabase.open(), platformOverride: PlatformHooks? = null) : AutoCloseable {
    val clock = Clock.SYSTEM
    private val localDates = SystemLocalDates()
    val preferences = Preferences(database.settings, clock)
    private val graphScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Read synchronously when a player is created; kept current from the stored setting. */
    @Volatile private var hardwareDecoding = false

    /** The player of this app (mpv); also needs the main window, given by [Main]. */
    val playerHooks = DesktopPlayerHooks { hardwareDecoding }
    // Without libmpv (not installed) videos open a screen that says so instead of crashing.
    private val platform: PlatformHooks = platformOverride ?: DesktopPlatform(playerHooks.takeIf { LibMpv.isAvailable() })

    init {
        graphScope.launch { preferences.observe(Settings.HardwareDecoding).collect { hardwareDecoding = it } }
        AppLog.sink = FileLogSink(DesktopPaths.cache.resolve("racha.log"))
        graphScope.launch { preferences.observe(Settings.LogLevelSetting).collect { AppLog.level = it } }
    }
    private val probe = MpvMediaProbe()
    private val hasher = Sha256FileHasher()

    val ensureBuiltInCategories = EnsureBuiltInCategories(database.categories, clock)
    val scanLibrary = ScanLibrary(
        database.categories, database.library, database.progress, NioLibrarySource(), probe, hasher, preferences, clock, localDates,
    )
    private val notifier = DesktopNotifier()
    private val notifyNewContent = NotifyNewContent(database.library, preferences, clock, notifier)
    private val studyReminder = StudyReminder(preferences, database.history, database.progress, localDates, clock, notifier)

    val keepLibraryUpToDate = KeepLibraryUpToDate(
        database.categories,
        scanLibrary,
        HashEngagedElements(database.categories, database.library, hasher),
        RefreshThumbnails(database.categories, database.library, MpvThumbnailRenderer(DesktopPaths.thumbnails), preferences),
        WatchServiceLibraryWatcher(),
        preferences,
        indexResourceText = { IndexResourceText(database.categories, database.library, NioResourceFiles(), database.resourceText)() },
        newContent = notifyNewContent,
    )

    private val updateItemProgress = UpdateItemProgress(database.library, database.progress)

    // Sync (§7.4, opt-in): runs while the app is open; the session's refresh token lives in the secret service.
    private val appVersion = System.getProperty("racha.version") ?: "dev"
    private val syncServer = KtorSyncServer(options = {
        HttpOptions.of(preferences.get(Settings.Proxy), preferences.get(Settings.UserAgent), appVersion, "Linux")
    })
    val account = AccountService(
        syncServer, LibSecretCredentialStore(DesktopPaths.data.resolve("session")), database.sync, database.accounts, preferences,
        { DeviceDescriptor(null, deviceName(), "linux", appVersion) }, clock,
    )
    /** Usage events and client errors for the account's server (§8); nothing is kept while signed out. */
    val telemetry = Telemetry(database.telemetry, preferences, clock, "linux", appVersion)
    private val telemetryUploader = TelemetryUploader(database.telemetry, syncServer, account, preferences, clock)
    private val syncEngine = SyncEngine(database.sync, syncServer, account, preferences, clock, telemetry, NotifySyncFailure(preferences, clock, notifier))
    private val syncScheduler = SyncScheduler(syncEngine, preferences) { telemetryUploader.upload() }

    /** The daily reminder (§9) while the app is open: on Linux there is no system scheduler for a closed app. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun startReminder() {
        graphScope.launch {
            combine(preferences.observe(Settings.DailyReminder), preferences.observe(Settings.DailyReminderTime)) { on, time -> on to time }
                .collectLatest { (on, time) ->
                    while (on) {
                        delay(ReminderTime.untilNext(time).toMillis())
                        studyReminder.remindIfDue()
                    }
                }
        }
    }

    /** Restores the session and keeps syncing until the app closes. */
    fun startSync() {
        graphScope.launch {
            account.restore()
            telemetry.appOpened()
            syncScheduler.run(this, NetworkMonitor.online())
        }
    }

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
        searchLibrary = SearchLibrary(database.library, database.resourceText),
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
        sync = SyncControls(account, syncEngine, syncScheduler, database.sync),
        manageCategories = ManageCategories(database.categories, scanLibrary, clock),
        storage = StorageMaintenance(database.library, database.history, DirectoryThumbnailCache(DesktopPaths.thumbnails), keepLibraryUpToDate),
        configTransfer = ConfigTransfer(preferences, database.categories, clock, appVersion),
        loadPersonalStats = LoadPersonalStats(
            database.categories, database.library, database.progress, database.history, database.stats, localDates, clock,
            RemoteStatsSource(syncServer, account, preferences) { java.time.ZoneId.systemDefault().id },
        ),
    )

    override fun close() {
        graphScope.cancel()
        probe.close()
        syncServer.close()
    }

    private fun deviceName(): String =
        System.getenv("HOSTNAME")?.takeIf { it.isNotBlank() } ?: runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("Linux")
}
