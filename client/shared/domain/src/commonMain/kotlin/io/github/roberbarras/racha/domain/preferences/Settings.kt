package io.github.roberbarras.racha.domain.preferences

import io.github.roberbarras.racha.domain.library.Mode as LibraryMode
import io.github.roberbarras.racha.domain.preferences.SettingScope.DEVICE
import io.github.roberbarras.racha.domain.preferences.SettingScope.USER

enum class AppLanguage { SYSTEM, ES, EN }

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class VideoScale { FIT, FILL, STRETCH }

enum class ChangeDetection { MTIME, HASH }

enum class ConflictPolicy { LAST_WRITE_WINS, ASK }

enum class LogLevel { ERROR, WARN, INFO, DEBUG }

/**
 * Catalog of every setting in spec §9 with its default. Per-category options (roots, exclusions, primary category,
 * study/leisure visibility) live on [io.github.roberbarras.racha.domain.library.Category]. Secrets (sync token, image
 * AI API key) are not settings: they belong to the platform credential store and are never exported (ADR-0010).
 */
object Settings {
    // General
    val Language = EnumSetting("general.language", USER, AppLanguage.SYSTEM, AppLanguage.entries)
    val Theme = EnumSetting("general.theme", USER, ThemeMode.SYSTEM, ThemeMode.entries)
    val AccentColor = StringSetting("general.accentColor", USER, "#E8812A", Regex("#[0-9A-Fa-f]{6}"))
    /** The tone of the backgrounds and surfaces; "" keeps Racha's (theme colours chosen by the user). */
    val BackgroundColor = StringSetting("general.backgroundColor", USER, "", Regex("(#[0-9A-Fa-f]{6})?"))
    /** The colour of the dark panels (Continue watching, the narrow navigation bar, the player's panels); "" keeps Racha's. */
    val RailColor = StringSetting("general.railColor", USER, "", Regex("(#[0-9A-Fa-f]{6})?"))
    val FontScale = DoubleSetting("general.fontScale", USER, 1.0, 0.85..1.3)

    /** First-run guide finished (or skipped) on this device. */
    val OnboardingDone = BooleanSetting("general.onboardingDone", DEVICE, false)

    /** Interface size on desktop; 0 = what the desktop announces (Xft.dpi). Per device: screens differ. */
    val UiScale = DoubleSetting("general.uiScale", DEVICE, 0.0, 0.0..3.0)

    // Mode
    val Mode = EnumSetting("mode.current", USER, LibraryMode.STUDY, LibraryMode.entries)

    // Player
    val DefaultSpeed = DoubleSetting("player.defaultSpeed", USER, 1.0, 0.25..4.0)
    val SpeedSteps = DoubleListSetting(
        "player.speedSteps", USER, listOf(0.25, 0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0, 3.0, 4.0), 0.25..4.0,
    )
    val SubtitlesEnabled = BooleanSetting("player.subtitlesEnabled", USER, false)

    /** Empty means "the app language" (spec §3.2: the app language filters subtitles by default). */
    val SubtitleLanguage = StringSetting("player.subtitleLanguage", USER, "", Regex("|[a-z]{2,3}(-[a-z0-9]{2,8})?"))
    val SeekStepSeconds = IntSetting("player.seekStepSeconds", USER, 10, allowed = setOf(5, 10, 30))
    val GestureTapEdges = BooleanSetting("player.gesture.tapEdges", USER, true)
    val GestureDoubleTap = BooleanSetting("player.gesture.doubleTap", USER, true)
    val GestureSwipeSeek = BooleanSetting("player.gesture.swipeSeek", USER, true)
    val GestureSwipeBrightness = BooleanSetting("player.gesture.swipeBrightness", USER, true)
    val GestureSwipeVolume = BooleanSetting("player.gesture.swipeVolume", USER, true)
    val GesturePinchZoom = BooleanSetting("player.gesture.pinchZoom", USER, true)
    val GestureLongPressSpeed = BooleanSetting("player.gesture.longPressSpeed", USER, true)
    val LongPressSpeed = DoubleSetting("player.longPressSpeed", USER, 2.0, 0.25..4.0)
    val AutoplayNext = BooleanSetting("player.autoplayNext", USER, true)
    val SkipIntroSeconds = IntSetting("player.skipIntroSeconds", USER, 0, range = 0..600)
    val SkipOutroSeconds = IntSetting("player.skipOutroSeconds", USER, 0, range = 0..600)
    val ShowSidebar = BooleanSetting("player.showSidebar", USER, false)
    val Zoom = EnumSetting("player.zoom", USER, VideoScale.FIT, VideoScale.entries)

    /** Desktop only, experimental (ADR-0011). Android always uses the platform decoders. */
    val HardwareDecoding = BooleanSetting("player.hardwareDecoding", DEVICE, false)
    /** AI summary providers in the user's order (`SummaryProvider.encode`); per device, like their keys (H17). */
    val SummaryProviders = StringSetting("ai.summaryProviders", DEVICE, "")
    /** Keyboard shortcuts changed by the user, over the defaults (`Shortcuts.encode`); per device, keyboards differ (H15). */
    val PlayerShortcuts = StringSetting("player.shortcuts", DEVICE, "")

    // Progress
    val SaveIntervalSeconds = IntSetting("progress.saveIntervalSeconds", USER, 60, allowed = setOf(15, 30, 60, 300))

    // Scanning
    val AutoScan = BooleanSetting("scan.automatic", DEVICE, true)
    val FileWatcher = BooleanSetting("scan.fileWatcher", DEVICE, true)
    val Thumbnails = BooleanSetting("scan.thumbnails", DEVICE, true)
    val ChangeDetectionMode = EnumSetting("scan.changeDetection", DEVICE, ChangeDetection.MTIME, ChangeDetection.entries)
    val AiCoverEnabled = BooleanSetting("scan.aiCover.enabled", USER, false)
    val AiCoverEndpoint = StringSetting("scan.aiCover.endpoint", USER, "")
    val AiCoverPrompt = StringSetting("scan.aiCover.prompt", USER, "")

    // Sync (connection settings are per device: they are needed before signing in)
    val SyncEnabled = BooleanSetting("sync.enabled", DEVICE, false)
    val SyncHost = StringSetting("sync.host", DEVICE, "")
    val SyncPort = IntSetting("sync.port", DEVICE, 443, range = 1..65535)
    val SyncHttps = BooleanSetting("sync.https", DEVICE, true)
    val SyncBasePath = StringSetting("sync.basePath", DEVICE, "")
    val SyncUsername = StringSetting("sync.username", DEVICE, "")
    val SyncIntervalMinutes = IntSetting("sync.intervalMinutes", DEVICE, 5, range = 1..1440)
    val SyncConflictPolicy = EnumSetting("sync.conflictPolicy", USER, ConflictPolicy.ASK, ConflictPolicy.entries)

    // Notifications
    val NotifyNewContent = BooleanSetting("notifications.newContent", DEVICE, true)
    val NotifySyncFailed = BooleanSetting("notifications.syncFailed", DEVICE, true)
    val DailyReminder = BooleanSetting("notifications.dailyReminder", DEVICE, false)
    val DailyReminderTime = StringSetting("notifications.dailyReminderTime", DEVICE, "19:00", Regex("([01][0-9]|2[0-3]):[0-5][0-9]"))

    // Advanced
    val LogLevelSetting = EnumSetting("advanced.logLevel", DEVICE, LogLevel.INFO, LogLevel.entries)

    /** Empty means "the sync server". */
    val TelemetryEndpoint = StringSetting("advanced.telemetryEndpoint", USER, "")
    val Proxy = StringSetting("advanced.proxy", DEVICE, "")

    /** Empty means the default `Racha/<version> (<platform>)`. */
    val UserAgent = StringSetting("advanced.userAgent", DEVICE, "")

    val all: List<Setting<*>> = listOf(
        Language, Theme, AccentColor, BackgroundColor, RailColor, FontScale, UiScale, OnboardingDone, Mode,
        DefaultSpeed, SpeedSteps, SubtitlesEnabled, SubtitleLanguage, SeekStepSeconds,
        GestureTapEdges, GestureDoubleTap, GestureSwipeSeek, GestureSwipeBrightness, GestureSwipeVolume,
        GesturePinchZoom, GestureLongPressSpeed, LongPressSpeed, AutoplayNext, SkipIntroSeconds, SkipOutroSeconds,
        ShowSidebar, Zoom, HardwareDecoding, PlayerShortcuts, SummaryProviders, SaveIntervalSeconds,
        AutoScan, FileWatcher, Thumbnails, ChangeDetectionMode, AiCoverEnabled, AiCoverEndpoint, AiCoverPrompt,
        SyncEnabled, SyncHost, SyncPort, SyncHttps, SyncBasePath, SyncUsername, SyncIntervalMinutes, SyncConflictPolicy,
        NotifyNewContent, NotifySyncFailed, DailyReminder, DailyReminderTime,
        LogLevelSetting, TelemetryEndpoint, Proxy, UserAgent,
    )

    private val byKey: Map<String, Setting<*>> = all.associateBy { it.key }

    fun byKey(key: String): Setting<*>? = byKey[key]
}
