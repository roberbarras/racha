package io.github.roberbarras.racha.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import io.github.roberbarras.racha.domain.playback.KeyCombo
import io.github.roberbarras.racha.domain.playback.PlayerAction
import io.github.roberbarras.racha.domain.playback.Shortcuts
import io.github.roberbarras.racha.ui.design.ButtonKind
import io.github.roberbarras.racha.ui.design.RButton
import io.github.roberbarras.racha.ui.design.RText
import io.github.roberbarras.racha.ui.design.Racha
import io.github.roberbarras.racha.ui.resources.Res
import io.github.roberbarras.racha.ui.resources.cancel
import io.github.roberbarras.racha.ui.resources.key_space
import io.github.roberbarras.racha.ui.resources.shortcut_BOOKMARK
import io.github.roberbarras.racha.ui.resources.shortcut_FULLSCREEN
import io.github.roberbarras.racha.ui.resources.shortcut_HELP
import io.github.roberbarras.racha.ui.resources.shortcut_MUTE
import io.github.roberbarras.racha.ui.resources.shortcut_NEXT
import io.github.roberbarras.racha.ui.resources.shortcut_PLAY_PAUSE
import io.github.roberbarras.racha.ui.resources.shortcut_PREVIOUS
import io.github.roberbarras.racha.ui.resources.shortcut_SEEK_BACK
import io.github.roberbarras.racha.ui.resources.shortcut_SEEK_FORWARD
import io.github.roberbarras.racha.ui.resources.shortcut_SPEED_DOWN
import io.github.roberbarras.racha.ui.resources.shortcut_SPEED_UP
import io.github.roberbarras.racha.ui.resources.shortcut_SUBTITLES
import io.github.roberbarras.racha.ui.resources.shortcut_VOLUME_DOWN
import io.github.roberbarras.racha.ui.resources.shortcut_VOLUME_UP
import io.github.roberbarras.racha.ui.resources.shortcut_escape
import io.github.roberbarras.racha.ui.resources.shortcuts_add
import io.github.roberbarras.racha.ui.resources.shortcuts_hint
import io.github.roberbarras.racha.ui.resources.shortcuts_none
import io.github.roberbarras.racha.ui.resources.shortcuts_press
import io.github.roberbarras.racha.ui.resources.shortcuts_reset
import io.github.roberbarras.racha.ui.resources.shortcuts_reset_all
import io.github.roberbarras.racha.ui.resources.shortcuts_taken
import io.github.roberbarras.racha.ui.resources.shortcuts_title
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun actionName(action: PlayerAction): String = stringResource(
    when (action) {
        PlayerAction.PLAY_PAUSE -> Res.string.shortcut_PLAY_PAUSE
        PlayerAction.SEEK_BACK -> Res.string.shortcut_SEEK_BACK
        PlayerAction.SEEK_FORWARD -> Res.string.shortcut_SEEK_FORWARD
        PlayerAction.VOLUME_UP -> Res.string.shortcut_VOLUME_UP
        PlayerAction.VOLUME_DOWN -> Res.string.shortcut_VOLUME_DOWN
        PlayerAction.MUTE -> Res.string.shortcut_MUTE
        PlayerAction.SPEED_DOWN -> Res.string.shortcut_SPEED_DOWN
        PlayerAction.SPEED_UP -> Res.string.shortcut_SPEED_UP
        PlayerAction.SUBTITLES -> Res.string.shortcut_SUBTITLES
        PlayerAction.FULLSCREEN -> Res.string.shortcut_FULLSCREEN
        PlayerAction.NEXT -> Res.string.shortcut_NEXT
        PlayerAction.PREVIOUS -> Res.string.shortcut_PREVIOUS
        PlayerAction.BOOKMARK -> Res.string.shortcut_BOOKMARK
        PlayerAction.HELP -> Res.string.shortcut_HELP
    },
)

/** The keys in a chip, the way the keyboard shows them. */
@Composable
private fun KeyChip(text: String) {
    val shape = RoundedCornerShape(Racha.radius.s)
    RText(
        text, style = Racha.type.label, color = Racha.colors.ink,
        modifier = Modifier.clip(shape).background(Racha.colors.surfaceSunken).border(1.dp, Racha.colors.border, shape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** Over the player (H or F1): what each key does. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ShortcutsHelp(shortcuts: Shortcuts, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val space = stringResource(Res.string.key_space)
    val shape = RoundedCornerShape(Racha.radius.l)
    Column(
        modifier.widthIn(max = 560.dp).clip(shape).background(Racha.colors.surface).padding(Racha.space.l),
        verticalArrangement = Arrangement.spacedBy(Racha.space.s),
    ) {
        RText(stringResource(Res.string.shortcuts_title), style = Racha.type.heading)
        (PlayerAction.entries.map { it to shortcuts.keysOf(it).map { k -> Keys.label(k, space) } } +
            listOf(null to listOf("Esc"))).forEach { (action, keys) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Racha.space.m)) {
                RText(action?.let { actionName(it) } ?: stringResource(Res.string.shortcut_escape), style = Racha.type.body, modifier = Modifier.weight(1f))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Racha.space.xs)) {
                    if (keys.isEmpty()) RText("—", style = Racha.type.body, color = Racha.colors.inkSubtle)
                    keys.forEach { KeyChip(it) }
                }
            }
        }
        RButton(stringResource(Res.string.cancel), onClose, kind = ButtonKind.Ghost)
    }
}

/**
 * Settings → Player (Linux): every action with its keys; "Add" waits for the next combination. A key used elsewhere
 * moves here and the screen says from where; Escape cancels and can never be assigned.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShortcutsEditor(shortcuts: Shortcuts, onChange: (Shortcuts) -> Unit) {
    val space = stringResource(Res.string.key_space)
    var capturing by remember { mutableStateOf<PlayerAction?>(null) }
    var moved by remember { mutableStateOf<Pair<PlayerAction, PlayerAction>?>(null) } // (to, from)
    Column(verticalArrangement = Arrangement.spacedBy(Racha.space.m)) {
        RText(stringResource(Res.string.shortcuts_hint), style = Racha.type.caption, color = Racha.colors.inkMuted)
        PlayerAction.entries.forEach { action ->
            Column(verticalArrangement = Arrangement.spacedBy(Racha.space.xs)) {
                FlowRow(
                    Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Racha.space.s),
                    verticalArrangement = Arrangement.spacedBy(Racha.space.xs), itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    RText(actionName(action), style = Racha.type.bodyStrong, modifier = Modifier.widthIn(min = 180.dp))
                    val keys = shortcuts.keysOf(action)
                    if (keys.isEmpty()) RText(stringResource(Res.string.shortcuts_none), style = Racha.type.body, color = Racha.colors.inkSubtle)
                    keys.forEach { combo ->
                        // Pressing a key's chip removes it.
                        RButton("${Keys.label(combo, space)}  ✕", { onChange(shortcuts.removing(action, combo)) }, kind = ButtonKind.Secondary)
                    }
                    if (capturing == action) {
                        Capture(
                            onCombo = { combo ->
                                val previous = shortcuts.actionFor(combo)?.takeIf { it != action }
                                onChange(shortcuts.adding(action, combo))
                                moved = previous?.let { action to it }
                                capturing = null
                            },
                            onCancel = { capturing = null },
                        )
                    } else {
                        RButton(stringResource(Res.string.shortcuts_add), { capturing = action; moved = null }, kind = ButtonKind.Ghost)
                    }
                    if (keys != Shortcuts.DEFAULT.keysOf(action)) {
                        RButton(stringResource(Res.string.shortcuts_reset), { onChange(shortcuts.reset(action)) }, kind = ButtonKind.Ghost)
                    }
                }
                moved?.takeIf { it.first == action }?.let { (_, from) ->
                    RText(stringResource(Res.string.shortcuts_taken, actionName(from)), style = Racha.type.caption, color = Racha.colors.inkMuted)
                }
            }
        }
        if (shortcuts != Shortcuts.DEFAULT) {
            RButton(stringResource(Res.string.shortcuts_reset_all), { onChange(Shortcuts.DEFAULT); moved = null }, kind = ButtonKind.Secondary)
        }
    }
}

/** Takes the next key press as a combination; Escape cancels. */
@Composable
private fun Capture(onCombo: (KeyCombo) -> Unit, onCancel: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val shape = RoundedCornerShape(Racha.radius.s)
    RText(
        stringResource(Res.string.shortcuts_press), style = Racha.type.label, color = Racha.colors.ink,
        modifier = Modifier.clip(shape).background(Racha.colors.accentSoft).padding(horizontal = 10.dp, vertical = 6.dp)
            .focusRequester(focus).focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent true
                if (event.key == Key.Escape) {
                    onCancel()
                    return@onPreviewKeyEvent true
                }
                Keys.combo(event)?.let(onCombo)
                true
            },
    )
}
