package io.github.roberbarras.racha.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.roberbarras.racha.application.preferences.InvalidConfigFile
import io.github.roberbarras.racha.application.sync.AccountState
import io.github.roberbarras.racha.domain.preferences.AppLanguage
import io.github.roberbarras.racha.domain.preferences.ChangeDetection
import io.github.roberbarras.racha.domain.preferences.LogLevel
import io.github.roberbarras.racha.domain.preferences.Setting
import io.github.roberbarras.racha.domain.preferences.Settings
import io.github.roberbarras.racha.domain.preferences.ThemeMode
import io.github.roberbarras.racha.domain.preferences.VideoScale
import io.github.roberbarras.racha.ui.app.RachaDependencies
import io.github.roberbarras.racha.ui.account.settingName
import io.github.roberbarras.racha.ui.common.ResponsiveColumn
import io.github.roberbarras.racha.ui.common.ScreenTitle
import io.github.roberbarras.racha.ui.common.fileSize
import io.github.roberbarras.racha.ui.common.parseColor
import io.github.roberbarras.racha.ui.design.ButtonKind
import io.github.roberbarras.racha.ui.design.RButton
import io.github.roberbarras.racha.ui.design.RText
import io.github.roberbarras.racha.ui.design.Racha
import io.github.roberbarras.racha.ui.design.RachaIcons
import io.github.roberbarras.racha.ui.design.interactive
import io.github.roberbarras.racha.ui.resources.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** Results of the Storage actions, worded by the screen. */
sealed interface StorageResult {
    data class Freed(val bytes: Long) : StorageResult
    data object Done : StorageResult
    data object Exported : StorageResult
    data class Imported(val settings: Int, val categories: Int) : StorageResult
    data object InvalidFile : StorageResult
}

class SettingsViewModel(val deps: RachaDependencies) : ViewModel() {
    private val flows = mutableMapOf<String, StateFlow<*>>()
    private var stored: Map<String, String> = emptyMap()

    /** The sections are shown once the stored values are read (one query), so no switch flickers from its default. */
    val ready = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            stored = deps.preferences.changed()
            ready.value = true
        }
    }

    /** The live value of [setting], starting from the stored one. */
    @Suppress("UNCHECKED_CAST")
    fun <T> value(setting: Setting<T>): StateFlow<T> = flows.getOrPut(setting.key) {
        deps.preferences.observe(setting).stateIn(viewModelScope, SharingStarted.Eagerly, setting.decodeOrDefault(stored[setting.key]))
    } as StateFlow<T>

    fun <T> set(setting: Setting<T>, value: T) = viewModelScope.launch { runCatching { deps.preferences.set(setting, value) } }

    val account: StateFlow<AccountState?> = (deps.sync?.account ?: flowOf(null)).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val results = MutableStateFlow<Map<String, StorageResult>>(emptyMap())

    private fun result(key: String, value: StorageResult) {
        results.value = results.value + (key to value)
    }

    fun clearThumbnails() = viewModelScope.launch { result("thumbnails", StorageResult.Freed(deps.storage.clearThumbnails())) }
    fun clearMetadata() = viewModelScope.launch { deps.storage.clearMetadata(); result("metadata", StorageResult.Done) }
    fun clearHistory() = viewModelScope.launch { deps.storage.clearHistory(); result("history", StorageResult.Done) }

    fun export() = viewModelScope.launch {
        val text = deps.configTransfer.export()
        if (deps.platform.saveTextFile("racha-config.json", text)) result("export", StorageResult.Exported)
    }

    fun import() = viewModelScope.launch {
        val text = deps.platform.openTextFile() ?: return@launch
        result("import", try {
            deps.configTransfer.import(text).let { StorageResult.Imported(it.settings, it.categories) }
        } catch (e: InvalidConfigFile) {
            StorageResult.InvalidFile
        })
    }
}

@Composable
private fun <T> SettingsViewModel.state(setting: Setting<T>): State<T> = value(setting).collectAsState()

/** Settings (spec §9): every option, applied as it changes. */
@Composable
fun SettingsScreen(deps: RachaDependencies, onFolders: () -> Unit, onAccount: (() -> Unit)?) {
    val vm = viewModel { SettingsViewModel(deps) }
    val ready by vm.ready.collectAsStateWithLifecycle()
    ResponsiveColumn(maxContentWidth = SETTINGS_WIDTH) { _ ->
        item { ScreenTitle(stringResource(Res.string.settings_title), stringResource(Res.string.settings_message)) }
        if (!ready) return@ResponsiveColumn
        item(key = "general") { GeneralSection(vm) }
        item(key = "categories") { CategoriesSection(deps, onFolders) }
        item(key = "player") { PlayerSection(vm) }
        item(key = "progress") { ProgressSection(vm) }
        item(key = "scan") { ScanSection(vm) }
        item(key = "sync") { SyncSection(vm, onAccount) }
        item(key = "notifications") { NotificationsSection(vm) }
        item(key = "storage") { StorageSection(vm) }
        item(key = "advanced") { AdvancedSection(vm) }
        item(key = "about") { AboutSection(deps) }
    }
}

private val SETTINGS_WIDTH = 880.dp

@Composable
private fun GeneralSection(vm: SettingsViewModel) = SettingsSection(stringResource(Res.string.section_general), RachaIcons.Settings) {
    val language by vm.state(Settings.Language)
    ChoiceRow(
        settingName(Settings.Language.key),
        listOf(
            stringResource(Res.string.language_system) to AppLanguage.SYSTEM,
            stringResource(Res.string.language_es) to AppLanguage.ES,
            stringResource(Res.string.language_en) to AppLanguage.EN,
        ),
        language, { vm.set(Settings.Language, it) },
    )
    Hairline()
    val theme by vm.state(Settings.Theme)
    ChoiceRow(
        settingName(Settings.Theme.key),
        listOf(
            stringResource(Res.string.theme_system) to ThemeMode.SYSTEM,
            stringResource(Res.string.theme_light) to ThemeMode.LIGHT,
            stringResource(Res.string.theme_dark) to ThemeMode.DARK,
        ),
        theme, { vm.set(Settings.Theme, it) },
    )
    Hairline()
    val accent by vm.state(Settings.AccentColor)
    AccentRow(accent) { vm.set(Settings.AccentColor, it) }
    Hairline()
    val fontScale by vm.state(Settings.FontScale)
    StepperRow(
        settingName(Settings.FontScale.key), stringResource(Res.string.percent, (fontScale * 100).toInt()),
        onMinus = (fontScale - 0.05).takeIf { it >= 0.849 }?.let { { vm.set(Settings.FontScale, round2(it)) } },
        onPlus = (fontScale + 0.05).takeIf { it <= 1.301 }?.let { { vm.set(Settings.FontScale, round2(it)) } },
    )
    vm.deps.platform.detectedUiScale?.let { detected ->
        Hairline()
        val uiScale by vm.state(Settings.UiScale)
        val index = UI_SCALES.indexOfFirst { it >= uiScale - 0.001 }.coerceAtLeast(0)
        StepperRow(
            stringResource(Res.string.setting_general_uiScale),
            if (uiScale <= 0.0) stringResource(Res.string.ui_scale_auto, (detected * 100).toInt()) else stringResource(Res.string.percent, (uiScale * 100).toInt()),
            onMinus = UI_SCALES.getOrNull(index - 1)?.let { { vm.set(Settings.UiScale, it) } },
            onPlus = UI_SCALES.getOrNull(index + 1)?.let { { vm.set(Settings.UiScale, it) } },
            description = stringResource(Res.string.ui_scale_hint),
        )
    }
}

private val UI_SCALES = listOf(0.0, 0.75, 0.9, 1.0, 1.1, 1.25, 1.5, 1.75, 2.0, 2.5, 3.0)
private val ACCENTS = listOf("#E8812A", "#E05A47", "#D65DB1", "#7B61FF", "#3A86FF", "#2A9D8F", "#2D6A4F", "#C9A227")

private fun round2(value: Double) = kotlin.math.round(value * 100) / 100

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccentRow(selected: String, onSelect: (String) -> Unit) {
    FlowRow(
        Modifier, horizontalArrangement = Arrangement.spacedBy(Racha.space.m), verticalArrangement = Arrangement.spacedBy(Racha.space.s),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        RText(settingName(Settings.AccentColor.key), style = Racha.type.bodyStrong, modifier = Modifier.weight(1f).widthIn(min = 160.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(Racha.space.s)) {
            ACCENTS.forEach { hex ->
                val isSelected = hex.equals(selected, ignoreCase = true)
                // The chosen colour gets a ring in the text colour, so it is clear without relying on size alone.
                androidx.compose.foundation.layout.Box(
                    Modifier.size(32.dp).clip(CircleShape)
                        .then(if (isSelected) Modifier.border(2.dp, Racha.colors.ink, CircleShape) else Modifier)
                        .padding(if (isSelected) 4.dp else 2.dp).clip(CircleShape)
                        .background(parseColor(hex))
                        .interactive({ onSelect(hex) }, CircleShape)
                        .semantics { contentDescription = hex; this.selected = isSelected },
                )
            }
        }
    }
}

@Composable
private fun PlayerSection(vm: SettingsViewModel) = SettingsSection(stringResource(Res.string.section_player), RachaIcons.Play) {
    val steps by vm.state(Settings.SpeedSteps)
    val speed by vm.state(Settings.DefaultSpeed)
    val speedIndex = steps.indexOfFirst { it >= speed - 0.001 }.coerceAtLeast(0)
    StepperRow(
        settingName(Settings.DefaultSpeed.key), "${speed}x",
        onMinus = steps.getOrNull(speedIndex - 1)?.let { { vm.set(Settings.DefaultSpeed, it) } },
        onPlus = steps.getOrNull(speedIndex + 1)?.let { { vm.set(Settings.DefaultSpeed, it) } },
    )
    Hairline()
    SpeedStepsRow(steps) { vm.set(Settings.SpeedSteps, it) }
    Hairline()
    val seek by vm.state(Settings.SeekStepSeconds)
    ChoiceRow(settingName(Settings.SeekStepSeconds.key), listOf(5, 10, 30).map { stringResource(Res.string.seconds_short, it) to it }, seek, { vm.set(Settings.SeekStepSeconds, it) })
    Hairline()
    val subtitles by vm.state(Settings.SubtitlesEnabled)
    SwitchRow(settingName(Settings.SubtitlesEnabled.key), subtitles, { vm.set(Settings.SubtitlesEnabled, it) })
    val subtitleLanguage by vm.state(Settings.SubtitleLanguage)
    TextRow(
        settingName(Settings.SubtitleLanguage.key), subtitleLanguage, { vm.set(Settings.SubtitleLanguage, it.lowercase()) },
        description = stringResource(Res.string.subtitle_language_hint),
        validate = { Settings.SubtitleLanguage.accepts(it.trim().lowercase()) },
    )
    Hairline()
    val autoplay by vm.state(Settings.AutoplayNext)
    SwitchRow(settingName(Settings.AutoplayNext.key), autoplay, { vm.set(Settings.AutoplayNext, it) })
    val sidebar by vm.state(Settings.ShowSidebar)
    SwitchRow(settingName(Settings.ShowSidebar.key), sidebar, { vm.set(Settings.ShowSidebar, it) })
    val intro by vm.state(Settings.SkipIntroSeconds)
    SecondsStepper(settingName(Settings.SkipIntroSeconds.key), intro) { vm.set(Settings.SkipIntroSeconds, it) }
    val outro by vm.state(Settings.SkipOutroSeconds)
    SecondsStepper(settingName(Settings.SkipOutroSeconds.key), outro) { vm.set(Settings.SkipOutroSeconds, it) }
    val zoom by vm.state(Settings.Zoom)
    ChoiceRow(
        settingName(Settings.Zoom.key),
        listOf(stringResource(Res.string.zoom_fit) to VideoScale.FIT, stringResource(Res.string.zoom_fill) to VideoScale.FILL, stringResource(Res.string.zoom_stretch) to VideoScale.STRETCH),
        zoom, { vm.set(Settings.Zoom, it) },
    )
    if (vm.deps.platform.hardwareDecodingSwitch) {
        val hw by vm.state(Settings.HardwareDecoding)
        SwitchRow(stringResource(Res.string.setting_player_hardwareDecoding), hw, { vm.set(Settings.HardwareDecoding, it) }, stringResource(Res.string.hardware_decoding_hint))
    }
    Hairline()
    RText(stringResource(Res.string.gestures_title).uppercase(), style = Racha.type.overline, color = Racha.colors.inkMuted)
    listOf(
        Settings.GestureTapEdges, Settings.GestureDoubleTap, Settings.GestureSwipeSeek, Settings.GestureSwipeBrightness,
        Settings.GestureSwipeVolume, Settings.GesturePinchZoom, Settings.GestureLongPressSpeed,
    ).forEach { gesture ->
        val on by vm.state(gesture)
        SwitchRow(settingName(gesture.key), on, { vm.set(gesture, it) })
    }
    val hold by vm.state(Settings.LongPressSpeed)
    val holdIndex = HOLD_SPEEDS.indexOfFirst { it >= hold - 0.001 }.coerceAtLeast(0)
    StepperRow(
        settingName(Settings.LongPressSpeed.key), "${hold}x",
        onMinus = HOLD_SPEEDS.getOrNull(holdIndex - 1)?.let { { vm.set(Settings.LongPressSpeed, it) } },
        onPlus = HOLD_SPEEDS.getOrNull(holdIndex + 1)?.let { { vm.set(Settings.LongPressSpeed, it) } },
    )
}

private val HOLD_SPEEDS = listOf(1.25, 1.5, 1.75, 2.0, 2.5, 3.0, 4.0)
private val SPEED_CANDIDATES = listOf(0.25, 0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0, 2.5, 3.0, 3.5, 4.0)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpeedStepsRow(steps: List<Double>, onChange: (List<Double>) -> Unit) {
    androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(Racha.space.s)) {
        RText(settingName(Settings.SpeedSteps.key), style = Racha.type.bodyStrong)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Racha.space.s), verticalArrangement = Arrangement.spacedBy(Racha.space.s)) {
            SPEED_CANDIDATES.forEach { candidate ->
                val on = candidate in steps
                RButton(
                    "${candidate}x",
                    {
                        val next = if (on) steps - candidate else (steps + candidate).sorted()
                        if (next.isNotEmpty()) onChange(next)
                    },
                    kind = if (on) ButtonKind.Primary else ButtonKind.Secondary,
                    modifier = Modifier.semantics { selected = on },
                )
            }
        }
    }
}

@Composable
private fun SecondsStepper(title: String, seconds: Int, onChange: (Int) -> Unit) = StepperRow(
    title, if (seconds == 0) stringResource(Res.string.off) else stringResource(Res.string.seconds_short, seconds),
    onMinus = if (seconds > 0) { { onChange((seconds - 5).coerceAtLeast(0)) } } else null,
    onPlus = if (seconds < 600) { { onChange((seconds + 5).coerceAtMost(600)) } } else null,
)

@Composable
private fun ProgressSection(vm: SettingsViewModel) = SettingsSection(stringResource(Res.string.section_progress), RachaIcons.Clock) {
    val interval by vm.state(Settings.SaveIntervalSeconds)
    ChoiceRow(settingName(Settings.SaveIntervalSeconds.key), listOf(15, 30, 60, 300).map { stringResource(Res.string.seconds_short, it) to it }, interval, { vm.set(Settings.SaveIntervalSeconds, it) })
}

@Composable
private fun ScanSection(vm: SettingsViewModel) = SettingsSection(stringResource(Res.string.section_scan), RachaIcons.RefreshCw) {
    val auto by vm.state(Settings.AutoScan)
    SwitchRow(stringResource(Res.string.setting_scan_automatic), auto, { vm.set(Settings.AutoScan, it) })
    val watcher by vm.state(Settings.FileWatcher)
    SwitchRow(stringResource(Res.string.setting_scan_fileWatcher), watcher, { vm.set(Settings.FileWatcher, it) })
    val thumbnails by vm.state(Settings.Thumbnails)
    SwitchRow(stringResource(Res.string.setting_scan_thumbnails), thumbnails, { vm.set(Settings.Thumbnails, it) })
    val detection by vm.state(Settings.ChangeDetectionMode)
    ChoiceRow(
        stringResource(Res.string.setting_scan_changeDetection),
        listOf(stringResource(Res.string.change_mtime) to ChangeDetection.MTIME, stringResource(Res.string.change_hash) to ChangeDetection.HASH),
        detection, { vm.set(Settings.ChangeDetectionMode, it) },
    )
}

@Composable
private fun SyncSection(vm: SettingsViewModel, onAccount: (() -> Unit)?) = SettingsSection(stringResource(Res.string.section_sync), RachaIcons.Cloud) {
    val account by vm.account.collectAsStateWithLifecycle()
    val state = account
    RText(
        if (state is AccountState.SignedIn) stringResource(Res.string.sync_state_on, state.email) else stringResource(Res.string.sync_state_off),
        style = Racha.type.body, color = Racha.colors.inkMuted,
    )
    if (onAccount != null) RButton(stringResource(Res.string.sync_open_account), onAccount, kind = ButtonKind.Secondary, icon = RachaIcons.Cloud)
}

@Composable
private fun NotificationsSection(vm: SettingsViewModel) = SettingsSection(stringResource(Res.string.section_notifications), RachaIcons.Sparkles) {
    val newContent by vm.state(Settings.NotifyNewContent)
    SwitchRow(stringResource(Res.string.setting_notifications_newContent), newContent, { vm.set(Settings.NotifyNewContent, it) })
    val syncFailed by vm.state(Settings.NotifySyncFailed)
    SwitchRow(stringResource(Res.string.setting_notifications_syncFailed), syncFailed, { vm.set(Settings.NotifySyncFailed, it) })
    val reminder by vm.state(Settings.DailyReminder)
    SwitchRow(stringResource(Res.string.setting_notifications_dailyReminder), reminder, { vm.set(Settings.DailyReminder, it) })
    if (reminder) {
        val time by vm.state(Settings.DailyReminderTime)
        StepperRow(
            stringResource(Res.string.reminder_time), time,
            onMinus = { vm.set(Settings.DailyReminderTime, shiftTime(time, -REMINDER_STEP)) },
            onPlus = { vm.set(Settings.DailyReminderTime, shiftTime(time, REMINDER_STEP)) },
        )
    }
}

private const val REMINDER_STEP = 30

/** `19:00` ± minutes, wrapping around midnight. */
internal fun shiftTime(time: String, minutes: Int): String {
    val (h, m) = time.split(':').map { it.toIntOrNull() ?: 0 }
    val total = Math.floorMod(h * 60 + m + minutes, 24 * 60)
    return "${(total / 60).toString().padStart(2, '0')}:${(total % 60).toString().padStart(2, '0')}"
}

@Composable
private fun StorageSection(vm: SettingsViewModel) = SettingsSection(stringResource(Res.string.section_storage), RachaIcons.HardDrive) {
    val results by vm.results.collectAsStateWithLifecycle()
    @Composable
    fun text(key: String): String? = when (val r = results[key]) {
        is StorageResult.Freed -> stringResource(Res.string.cleared_bytes, fileSize(r.bytes))
        StorageResult.Done -> stringResource(Res.string.cleared)
        StorageResult.Exported -> stringResource(Res.string.exported)
        is StorageResult.Imported -> stringResource(Res.string.imported, r.settings, r.categories)
        StorageResult.InvalidFile -> stringResource(Res.string.import_invalid)
        null -> null
    }
    ActionRow(stringResource(Res.string.clear_thumbnails), stringResource(Res.string.clear_thumbnails_hint), stringResource(Res.string.clear), vm::clearThumbnails, text("thumbnails"))
    Hairline()
    ActionRow(stringResource(Res.string.clear_metadata), stringResource(Res.string.clear_metadata_hint), stringResource(Res.string.clear), vm::clearMetadata, text("metadata"))
    Hairline()
    ActionRow(
        stringResource(Res.string.clear_history), stringResource(Res.string.clear_history_hint), stringResource(Res.string.clear), vm::clearHistory,
        text("history"), confirmText = stringResource(Res.string.clear_history_hint), danger = true,
    )
    Hairline()
    ActionRow(stringResource(Res.string.export_config), stringResource(Res.string.export_config_hint), stringResource(Res.string.export), vm::export, text("export"))
    Hairline()
    ActionRow(stringResource(Res.string.import_config), stringResource(Res.string.import_config_hint), stringResource(Res.string.import), vm::import, text("import"))
}

@Composable
private fun AdvancedSection(vm: SettingsViewModel) = SettingsSection(stringResource(Res.string.section_advanced), RachaIcons.Gauge) {
    val level by vm.state(Settings.LogLevelSetting)
    ChoiceRow(stringResource(Res.string.setting_advanced_logLevel), LogLevel.entries.map { it.name.lowercase() to it }, level, { vm.set(Settings.LogLevelSetting, it) })
    Hairline()
    val endpoint by vm.state(Settings.TelemetryEndpoint)
    TextRow(
        settingName(Settings.TelemetryEndpoint.key), endpoint, { vm.set(Settings.TelemetryEndpoint, it) },
        description = stringResource(Res.string.telemetry_endpoint_hint), keyboardType = KeyboardType.Uri,
        validate = { it.isBlank() || it.trim().startsWith("http://") || it.trim().startsWith("https://") },
    )
    val proxy by vm.state(Settings.Proxy)
    TextRow(stringResource(Res.string.setting_advanced_proxy), proxy, { vm.set(Settings.Proxy, it) }, stringResource(Res.string.proxy_hint), KeyboardType.Uri)
    val agent by vm.state(Settings.UserAgent)
    TextRow(stringResource(Res.string.setting_advanced_userAgent), agent, { vm.set(Settings.UserAgent, it) }, stringResource(Res.string.user_agent_hint))
}

@Composable
private fun AboutSection(deps: RachaDependencies) = SettingsSection(stringResource(Res.string.section_about), RachaIcons.Flame) {
    RText(stringResource(Res.string.version, deps.platform.appVersion), style = Racha.type.bodyStrong)
    RText(stringResource(Res.string.license), style = Racha.type.body, color = Racha.colors.inkMuted)
    RButton(stringResource(Res.string.source_code), { deps.platform.openUrl(SOURCE_URL) }, kind = ButtonKind.Secondary, icon = RachaIcons.ExternalLink)
}

private const val SOURCE_URL = "https://github.com/roberbarras/racha"
