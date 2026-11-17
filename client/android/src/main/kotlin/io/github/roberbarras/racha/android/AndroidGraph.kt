package io.github.roberbarras.racha.android

import io.github.roberbarras.racha.application.backup.FullBackup
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
import io.github.roberbarras.racha.application.sync.ManagePassword
import io.github.roberbarras.racha.application.sync.SyncControls
import io.github.roberbarras.racha.application.sync.SyncEngine
import io.github.roberbarras.racha.application.sync.SyncScheduler
import io.github.roberbarras.racha.domain.preferences.Settings
import io.github.roberbarras.racha.remote.KtorSyncServer
import io.github.roberbarras.racha.remote.KtorImageGenerator
import io.github.roberbarras.racha.remote.KtorChatClient
import io.github.roberbarras.racha.application.ai.ProviderKeys
import io.github.roberbarras.racha.application.ai.SummarizeWithAi
import io.github.roberbarras.racha.application.ai.SummaryProviders
import io.github.roberbarras.racha.application.library.GenerateCover
import io.github.roberbarras.racha.android.sync.KeystoreSecretStore
import io.github.roberbarras.racha.files.DirectoryCoverFiles
import io.github.roberbarras.racha.android.widget.TodayWidget
import io.github.roberbarras.racha.application.browse.ObserveTodayCard
import kotlinx.coroutines.flow.debounce
import io.github.roberbarras.racha.android.notifications.AndroidNotifier
import io.github.roberbarras.racha.android.notifications.ReminderWorker
import io.github.roberbarras.racha.application.notifications.NotifyNewContent
import io.github.roberbarras.racha.application.notifications.NotifySyncFailure
import io.github.roberbarras.racha.application.notifications.StudyReminder
import io.github.roberbarras.racha.remote.HttpOptions
import io.github.roberbarras.racha.application.browse.ManageCategories
import io.github.roberbarras.racha.application.library.StorageMaintenance
import io.github.roberbarras.racha.application.preferences.ConfigTransfer
import io.github.roberbarras.racha.application.shared.AppLog
import io.github.roberbarras.racha.domain.preferences.LogLevel
import io.github.roberbarras.racha.files.DirectoryThumbnailCache
import android.util.Log
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
    private val thumbnails = File(context.cacheDir, "thumbs")
    val appVersion = appVersion(context)

    val ensureBuiltInCategories = EnsureBuiltInCategories(database.categories, clock)
    val scanLibrary = ScanLibrary(
        database.categories, database.library, database.progress, NioLibrarySource(), AndroidMediaProbe(), hasher, preferences, clock,
        localDates,
    )
    private val notifier = AndroidNotifier(context)
    private val notifyNewContent = NotifyNewContent(database.library, preferences, clock, notifier)
    val observeTodayCard = ObserveTodayCard(
        ObserveHome(database.categories, database.library, database.progress, database.history, preferences, clock, localDates),
    )
    val studyReminder = StudyReminder(preferences, database.history, database.progress, localDates, clock, notifier)

    val keepLibraryUpToDate = KeepLibraryUpToDate(
        database.categories,
        scanLibrary,
        HashEngagedElements(database.categories, database.library, hasher),
        RefreshThumbnails(database.categories, database.library, AndroidThumbnailRenderer(thumbnails), preferences),
        FileObserverLibraryWatcher(),
        preferences,
        indexResourceText = { IndexResourceText(database.categories, database.library, NioResourceFiles(), database.resourceText)() },
        newContent = notifyNewContent,
    )

    private val updateItemProgress = UpdateItemProgress(database.library, database.progress)
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Sync (§7.4, opt-in): in the foreground while the app is visible ([MainActivity]), otherwise via WorkManager.
    private val httpOptions: suspend () -> HttpOptions = {
        HttpOptions.of(preferences.get(Settings.Proxy), preferences.get(Settings.UserAgent), appVersion, "Android")
    }
    private val syncServer = KtorSyncServer(options = httpOptions)
    private val aiApiKey = KeystoreSecretStore(context, "ai-key.bin")
    /** One key per summary provider (H17), each in its own Keystore-encrypted file. */
    private val summaryKeys = ProviderKeys { id -> KeystoreSecretStore(context, "ai-summary-$id.bin") }
    private val wifi: android.net.wifi.WifiManager? = context.applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
    private val covers = DirectoryCoverFiles(File(context.filesDir, "covers").toPath())
    val account = AccountService(
        syncServer, KeystoreCredentialStore(context), database.sync, database.accounts, preferences,
        { DeviceDescriptor(null, deviceName(context), "android", appVersion(context)) }, clock,
    )
    /** Usage events and client errors for the account's server (§8); nothing is kept while signed out. */
    val telemetry = Telemetry(database.telemetry, preferences, clock, "android", appVersion(context))
    val telemetryUploader = TelemetryUploader(database.telemetry, syncServer, account, preferences, clock)
    val syncEngine = SyncEngine(database.sync, syncServer, account, preferences, clock, telemetry, NotifySyncFailure(preferences, clock, notifier))
    val syncScheduler = SyncScheduler(syncEngine, preferences) { telemetryUploader.upload() }
    val connectivity = ConnectivityMonitor(context)
    private val syncControls = SyncControls(account, syncEngine, syncScheduler, database.sync, ManagePassword(syncServer, account, preferences))

    init {
        val app = context.applicationContext
        AppLog.sink = LogcatSink
        appScope.launch { preferences.observe(Settings.LogLevelSetting).collect { AppLog.level = it } }
        appScope.launch {
            account.restore()
            telemetry.appOpened()
            combine(preferences.observe(Settings.SyncEnabled), preferences.observe(Settings.SyncIntervalMinutes)) { on, minutes -> on to minutes }
                .collect { (on, minutes) -> SyncWorker.schedule(app, on, minutes) }
        }
        // The widget follows the streak and today's turn while the app process lives (quiet: emits on change only).
        appScope.launch {
            @OptIn(kotlinx.coroutines.FlowPreview::class)
            observeTodayCard().debounce(WIDGET_DEBOUNCE_MS).collect { TodayWidget.render(app, it) }
        }
        appScope.launch {
            combine(preferences.observe(Settings.DailyReminder), preferences.observe(Settings.DailyReminderTime)) { on, time -> on to time }
                .collect { (on, time) -> ReminderWorker.schedule(app, on, time) }
        }
    }

    /** The app went to the background: anything still queued goes out with the next network. */
    fun flushSyncInBackground(context: Context) {
        appScope.launch {
            if (preferences.get(Settings.SyncEnabled) && database.sync.observe().first().pending > 0) SyncWorker.flushSoon(context)
        }
    }

    /** The app log goes to logcat (spec §9 "nivel de log"). */
    private object LogcatSink : AppLog.Sink {
        override fun write(level: LogLevel, tag: String, message: String, error: Throwable?) {
            val t = "Racha/$tag"
            when (level) {
                LogLevel.ERROR -> Log.e(t, message, error)
                LogLevel.WARN -> Log.w(t, message, error)
                LogLevel.INFO -> Log.i(t, message, error)
                LogLevel.DEBUG -> Log.d(t, message, error)
            }
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
            database.history, database.stats, preferences, updateItemProgress, clock, localDates, telemetry, database.playbackMemory,
        ) { platform.player?.language() ?: "en" }
    },
        appScope = appScope,
        sync = syncControls,
        manageCategories = ManageCategories(database.categories, scanLibrary, clock),
        storage = StorageMaintenance(database.library, database.history, DirectoryThumbnailCache(thumbnails.toPath()), keepLibraryUpToDate),
        configTransfer = ConfigTransfer(preferences, database.categories, clock, appVersion),
        fullBackup = FullBackup(
            database.sync, database.categories, preferences, database.playbackMemory, database.stats, scanLibrary, clock, "Racha $appVersion (Android)",
        ),
        generateCover = GenerateCover(database.library, preferences, aiApiKey, KtorImageGenerator(options = httpOptions), covers),
        aiApiKey = aiApiKey,
        summaries = SummarizeWithAi(
            database.library, database.categories, NioResourceFiles(), database.summaries, KtorChatClient(options = httpOptions), preferences,
            summaryKeys, clock,
        ) { platform.player?.language() ?: "en" },
        summaryProviders = SummaryProviders(preferences, summaryKeys),
        cast = io.github.roberbarras.racha.application.cast.CastServices(
            io.github.roberbarras.racha.remote.cast.NetworkCastDiscovery(::multicastLock),
            io.github.roberbarras.racha.remote.cast.CastConnectors(),
        ) { io.github.roberbarras.racha.remote.cast.LanMediaServer() },
        loadPersonalStats = LoadPersonalStats(
            database.categories, database.library, database.progress, database.history, database.stats, localDates, clock,
            RemoteStatsSource(syncServer, account, preferences) { java.util.TimeZone.getDefault().id },
        ),
    )

    /** Wi-Fi drops multicast to save battery: held only while "Enviar a" searches for TVs (H18). */
    private fun multicastLock(): AutoCloseable {
        val wifi = wifi ?: return AutoCloseable {}
        val lock = wifi.createMulticastLock("racha-cast").apply { setReferenceCounted(false); acquire() }
        return AutoCloseable { if (lock.isHeld) lock.release() }
    }

    private companion object {
        const val WIDGET_DEBOUNCE_MS = 2_000L

        fun deviceName(context: Context): String =
            android.provider.Settings.Global.getString(context.contentResolver, android.provider.Settings.Global.DEVICE_NAME)
                ?.takeIf { it.isNotBlank() } ?: "${Build.MANUFACTURER} ${Build.MODEL}"

        fun appVersion(context: Context): String =
            runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "dev"
    }
}
