package io.github.roberbarras.racha.ui.account

import androidx.compose.runtime.Composable
import io.github.roberbarras.racha.ui.resources.Res
import io.github.roberbarras.racha.ui.resources.setting_advanced_telemetryEndpoint
import io.github.roberbarras.racha.ui.resources.setting_general_accentColor
import io.github.roberbarras.racha.ui.resources.setting_general_backgroundColor
import io.github.roberbarras.racha.ui.resources.setting_general_railColor
import io.github.roberbarras.racha.ui.resources.setting_general_fontScale
import io.github.roberbarras.racha.ui.resources.setting_general_language
import io.github.roberbarras.racha.ui.resources.setting_general_theme
import io.github.roberbarras.racha.ui.resources.setting_mode_current
import io.github.roberbarras.racha.ui.resources.setting_player_autoplayNext
import io.github.roberbarras.racha.ui.resources.setting_player_defaultSpeed
import io.github.roberbarras.racha.ui.resources.setting_player_gesture_doubleTap
import io.github.roberbarras.racha.ui.resources.setting_player_gesture_longPressSpeed
import io.github.roberbarras.racha.ui.resources.setting_player_gesture_pinchZoom
import io.github.roberbarras.racha.ui.resources.setting_player_gesture_swipeBrightness
import io.github.roberbarras.racha.ui.resources.setting_player_gesture_swipeSeek
import io.github.roberbarras.racha.ui.resources.setting_player_gesture_swipeVolume
import io.github.roberbarras.racha.ui.resources.setting_player_gesture_tapEdges
import io.github.roberbarras.racha.ui.resources.setting_player_longPressSpeed
import io.github.roberbarras.racha.ui.resources.setting_player_seekStepSeconds
import io.github.roberbarras.racha.ui.resources.setting_player_showSidebar
import io.github.roberbarras.racha.ui.resources.setting_player_skipIntroSeconds
import io.github.roberbarras.racha.ui.resources.setting_player_skipOutroSeconds
import io.github.roberbarras.racha.ui.resources.setting_player_speedSteps
import io.github.roberbarras.racha.ui.resources.setting_player_subtitleLanguage
import io.github.roberbarras.racha.ui.resources.setting_player_subtitlesEnabled
import io.github.roberbarras.racha.ui.resources.setting_player_zoom
import io.github.roberbarras.racha.ui.resources.setting_progress_saveIntervalSeconds
import io.github.roberbarras.racha.ui.resources.setting_scan_aiCover_enabled
import io.github.roberbarras.racha.ui.resources.setting_scan_aiCover_endpoint
import io.github.roberbarras.racha.ui.resources.setting_scan_aiCover_prompt
import io.github.roberbarras.racha.ui.resources.setting_sync_conflictPolicy
import io.github.roberbarras.racha.ui.resources.value_off
import io.github.roberbarras.racha.ui.resources.value_on
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Readable names of the settings that sync between devices (the ones a conflict can be about). */
private val names: Map<String, StringResource> by lazy {
    mapOf(
        "general.language" to Res.string.setting_general_language,
        "general.theme" to Res.string.setting_general_theme,
        "general.accentColor" to Res.string.setting_general_accentColor,
        "general.backgroundColor" to Res.string.setting_general_backgroundColor,
        "general.railColor" to Res.string.setting_general_railColor,
        "general.fontScale" to Res.string.setting_general_fontScale,
        "mode.current" to Res.string.setting_mode_current,
        "player.defaultSpeed" to Res.string.setting_player_defaultSpeed,
        "player.speedSteps" to Res.string.setting_player_speedSteps,
        "player.subtitlesEnabled" to Res.string.setting_player_subtitlesEnabled,
        "player.subtitleLanguage" to Res.string.setting_player_subtitleLanguage,
        "player.seekStepSeconds" to Res.string.setting_player_seekStepSeconds,
        "player.gesture.tapEdges" to Res.string.setting_player_gesture_tapEdges,
        "player.gesture.doubleTap" to Res.string.setting_player_gesture_doubleTap,
        "player.gesture.swipeSeek" to Res.string.setting_player_gesture_swipeSeek,
        "player.gesture.swipeBrightness" to Res.string.setting_player_gesture_swipeBrightness,
        "player.gesture.swipeVolume" to Res.string.setting_player_gesture_swipeVolume,
        "player.gesture.pinchZoom" to Res.string.setting_player_gesture_pinchZoom,
        "player.gesture.longPressSpeed" to Res.string.setting_player_gesture_longPressSpeed,
        "player.longPressSpeed" to Res.string.setting_player_longPressSpeed,
        "player.autoplayNext" to Res.string.setting_player_autoplayNext,
        "player.skipIntroSeconds" to Res.string.setting_player_skipIntroSeconds,
        "player.skipOutroSeconds" to Res.string.setting_player_skipOutroSeconds,
        "player.showSidebar" to Res.string.setting_player_showSidebar,
        "player.zoom" to Res.string.setting_player_zoom,
        "progress.saveIntervalSeconds" to Res.string.setting_progress_saveIntervalSeconds,
        "scan.aiCover.enabled" to Res.string.setting_scan_aiCover_enabled,
        "scan.aiCover.endpoint" to Res.string.setting_scan_aiCover_endpoint,
        "scan.aiCover.prompt" to Res.string.setting_scan_aiCover_prompt,
        "sync.conflictPolicy" to Res.string.setting_sync_conflictPolicy,
        "advanced.telemetryEndpoint" to Res.string.setting_advanced_telemetryEndpoint,
    )
}

@Composable
fun settingName(key: String): String = names[key]?.let { stringResource(it) } ?: key

/** A stored value as the user would read it (booleans as on/off; the rest as stored). */
@Composable
fun settingValue(raw: String): String = when (raw) {
    "true" -> stringResource(Res.string.value_on)
    "false" -> stringResource(Res.string.value_off)
    else -> raw.removeSurrounding("\"")
}
