package io.github.roberbarras.racha.ui.player

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import io.github.roberbarras.racha.domain.playback.KeyCombo

/** Compose keys ↔ the platform-neutral names of [KeyCombo.KEYS] (H15). */
internal object Keys {
    private val names: Map<Key, String> = buildMap {
        listOf(
            Key.A, Key.B, Key.C, Key.D, Key.E, Key.F, Key.G, Key.H, Key.I, Key.J, Key.K, Key.L, Key.M,
            Key.N, Key.O, Key.P, Key.Q, Key.R, Key.S, Key.T, Key.U, Key.V, Key.W, Key.X, Key.Y, Key.Z,
        ).forEachIndexed { i, key -> put(key, ('A' + i).toString()) }
        listOf(Key.Zero, Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine)
            .forEachIndexed { i, key -> put(key, i.toString()) }
        listOf(Key.F1, Key.F2, Key.F3, Key.F4, Key.F5, Key.F6, Key.F7, Key.F8, Key.F9, Key.F10, Key.F11, Key.F12)
            .forEachIndexed { i, key -> put(key, "F${i + 1}") }
        put(Key.Spacebar, "Space"); put(Key.Enter, "Enter"); put(Key.Tab, "Tab"); put(Key.Backspace, "Backspace")
        put(Key.Delete, "Delete"); put(Key.Insert, "Insert")
        put(Key.DirectionLeft, "Left"); put(Key.DirectionRight, "Right"); put(Key.DirectionUp, "Up"); put(Key.DirectionDown, "Down")
        put(Key.PageUp, "PageUp"); put(Key.PageDown, "PageDown"); put(Key.MoveHome, "Home"); put(Key.MoveEnd, "End")
        put(Key.Comma, "Comma"); put(Key.Period, "Period"); put(Key.Minus, "Minus"); put(Key.Equals, "Equals")
        put(Key.Slash, "Slash"); put(Key.Backslash, "Backslash"); put(Key.Semicolon, "Semicolon"); put(Key.Apostrophe, "Apostrophe")
        put(Key.Grave, "Grave"); put(Key.LeftBracket, "LeftBracket"); put(Key.RightBracket, "RightBracket")
    }

    /** The combo this key press is; null for keys a shortcut cannot use (Escape, modifiers alone, Meta combos). */
    fun combo(event: KeyEvent): KeyCombo? {
        if (event.isMetaPressed) return null
        val name = names[event.key] ?: return null
        return KeyCombo(name, ctrl = event.isCtrlPressed, shift = event.isShiftPressed, alt = event.isAltPressed)
    }

    /** How a combo is shown: arrows and punctuation as symbols, [space] in the app's language. */
    fun label(combo: KeyCombo, space: String): String {
        val key = when (combo.key) {
            "Space" -> space
            "Left" -> "←"; "Right" -> "→"; "Up" -> "↑"; "Down" -> "↓"
            "Comma" -> ","; "Period" -> "."; "Minus" -> "-"; "Equals" -> "="; "Slash" -> "/"; "Backslash" -> "\\"
            "Semicolon" -> ";"; "Apostrophe" -> "'"; "Grave" -> "`"; "LeftBracket" -> "["; "RightBracket" -> "]"
            "PageUp" -> "PgUp"; "PageDown" -> "PgDn"
            else -> combo.key
        }
        return buildString {
            if (combo.ctrl) append("Ctrl + ")
            if (combo.alt) append("Alt + ")
            if (combo.shift) append("Shift + ")
            append(key)
        }
    }
}
