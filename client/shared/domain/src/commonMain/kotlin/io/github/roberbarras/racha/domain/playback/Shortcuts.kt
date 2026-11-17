package io.github.roberbarras.racha.domain.playback

/** What a key can do in the player (H15). Escape always goes back or leaves full screen: it is not configurable. */
enum class PlayerAction {
    PLAY_PAUSE, SEEK_BACK, SEEK_FORWARD, VOLUME_UP, VOLUME_DOWN, MUTE, SPEED_DOWN, SPEED_UP, SUBTITLES, FULLSCREEN,
    NEXT, PREVIOUS, BOOKMARK, HELP,
}

/**
 * A key and its modifiers, written as people read it: `Space`, `K`, `Ctrl+Right`, `Shift+Period`. Key names are
 * platform-neutral ([KEYS]); the UI maps its own key codes to them.
 */
data class KeyCombo(val key: String, val ctrl: Boolean = false, val shift: Boolean = false, val alt: Boolean = false) {
    init {
        require(key in KEYS) { "unknown key $key" }
    }

    override fun toString(): String = buildString {
        if (ctrl) append("Ctrl+")
        if (alt) append("Alt+")
        if (shift) append("Shift+")
        append(key)
    }

    companion object {
        /** Every key a shortcut may use. */
        val KEYS: Set<String> = buildSet {
            ('A'..'Z').forEach { add(it.toString()) }
            ('0'..'9').forEach { add(it.toString()) }
            (1..12).forEach { add("F$it") }
            addAll(listOf("Space", "Enter", "Tab", "Backspace", "Delete", "Insert", "Left", "Right", "Up", "Down", "PageUp", "PageDown",
                "Home", "End", "Comma", "Period", "Minus", "Equals", "Slash", "Backslash", "Semicolon", "Apostrophe", "Grave",
                "LeftBracket", "RightBracket"))
        }

        /** `Ctrl+Shift+Right` → the combo; null for anything else. */
        fun parse(text: String): KeyCombo? {
            val parts = text.trim().split('+').map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.isEmpty()) return null
            val modifiers = parts.dropLast(1).map { it.lowercase() }
            if (modifiers.any { it !in setOf("ctrl", "shift", "alt") }) return null
            val key = parts.last()
            if (key !in KEYS) return null
            return KeyCombo(key, ctrl = "ctrl" in modifiers, shift = "shift" in modifiers, alt = "alt" in modifiers)
        }
    }
}

/**
 * Which keys do what in the player. Built from [DEFAULT] plus the user's changes; a key belongs to one action only,
 * so assigning it elsewhere takes it from where it was.
 */
class Shortcuts private constructor(val bindings: Map<PlayerAction, List<KeyCombo>>) {

    fun keysOf(action: PlayerAction): List<KeyCombo> = bindings[action].orEmpty()

    fun actionFor(combo: KeyCombo): PlayerAction? = bindings.entries.firstOrNull { combo in it.value }?.key

    /** [action] gets [combos]; any of them used by another action is taken from it. */
    fun with(action: PlayerAction, combos: List<KeyCombo>): Shortcuts {
        val wanted = combos.distinct()
        return Shortcuts(bindings.mapValues { (a, keys) -> if (a == action) wanted else keys - wanted.toSet() } + (action to wanted))
    }

    /** Adds one key to [action] (taking it from another action if needed). */
    fun adding(action: PlayerAction, combo: KeyCombo): Shortcuts = with(action, keysOf(action) + combo)

    fun removing(action: PlayerAction, combo: KeyCombo): Shortcuts = with(action, keysOf(action) - combo)

    fun reset(action: PlayerAction): Shortcuts =
        DEFAULT.keysOf(action).fold(with(action, emptyList())) { acc, combo -> acc.adding(action, combo) }

    /** Only what differs from [DEFAULT]: `SEEK_BACK=Left,Ctrl+J;MUTE=` (empty: no key). */
    fun encode(): String = PlayerAction.entries.filter { keysOf(it) != DEFAULT.keysOf(it) }
        .joinToString(";") { a -> "${a.name}=" + keysOf(a).joinToString(",") }

    override fun equals(other: Any?) = other is Shortcuts && other.bindings == bindings

    override fun hashCode() = bindings.hashCode()

    companion object {
        val DEFAULT = Shortcuts(
            mapOf(
                PlayerAction.PLAY_PAUSE to listOf(KeyCombo("Space"), KeyCombo("K")),
                PlayerAction.SEEK_BACK to listOf(KeyCombo("Left"), KeyCombo("J")),
                PlayerAction.SEEK_FORWARD to listOf(KeyCombo("Right"), KeyCombo("L")),
                PlayerAction.VOLUME_UP to listOf(KeyCombo("Up")),
                PlayerAction.VOLUME_DOWN to listOf(KeyCombo("Down")),
                PlayerAction.MUTE to listOf(KeyCombo("M")),
                PlayerAction.SPEED_DOWN to listOf(KeyCombo("Comma")),
                PlayerAction.SPEED_UP to listOf(KeyCombo("Period")),
                PlayerAction.SUBTITLES to listOf(KeyCombo("C")),
                PlayerAction.FULLSCREEN to listOf(KeyCombo("F")),
                PlayerAction.NEXT to listOf(KeyCombo("N")),
                PlayerAction.PREVIOUS to listOf(KeyCombo("P")),
                PlayerAction.BOOKMARK to listOf(KeyCombo("B")),
                PlayerAction.HELP to listOf(KeyCombo("H"), KeyCombo("F1")),
            ),
        )

        /** The user's changes over the defaults; anything unreadable is ignored (the default stays). */
        fun decode(raw: String): Shortcuts {
            var result = DEFAULT
            raw.split(';').filter { '=' in it }.forEach { entry ->
                val action = runCatching { PlayerAction.valueOf(entry.substringBefore('=').trim()) }.getOrNull() ?: return@forEach
                val combos = entry.substringAfter('=').split(',').filter { it.isNotBlank() }.mapNotNull(KeyCombo::parse)
                result = result.with(action, combos)
            }
            return result
        }
    }
}
