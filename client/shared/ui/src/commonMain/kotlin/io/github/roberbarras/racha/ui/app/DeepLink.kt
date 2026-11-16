package io.github.roberbarras.racha.ui.app

import io.github.roberbarras.racha.domain.library.ContentId

/**
 * Deep links (spec §2.2 "abrir un elemento concreto"): `racha://item/<id>` opens a course/series, `racha://play/<id>`
 * an element (video in the player, resource in the viewer), plus a few screens. Notifications, the widget and other
 * apps use them; ids are the same on every device (ADR-0004), so a link works on any of them.
 */
sealed interface DeepLink {
    data class Item(val id: ContentId) : DeepLink
    data class Play(val id: ContentId) : DeepLink
    data object Account : DeepLink
    data object Stats : DeepLink
    data object Settings : DeepLink

    companion object {
        const val SCHEME = "racha"
        const val ACCOUNT = "$SCHEME://account"

        fun item(id: ContentId) = "$SCHEME://item/${id.value}"
        fun play(id: ContentId) = "$SCHEME://play/${id.value}"

        /** Null for anything that is not one of our links (wrong scheme, unknown target, malformed id). */
        fun parse(uri: String): DeepLink? {
            val rest = uri.trim().removePrefix("$SCHEME://").takeIf { it != uri.trim() } ?: return null
            val parts = rest.trimEnd('/').split('/').filter { it.isNotEmpty() }
            fun id() = parts.getOrNull(1)?.takeIf { parts.size == 2 && ID.matches(it) }?.let(::ContentId)
            return when (parts.firstOrNull()) {
                "item" -> id()?.let(::Item)
                "play" -> id()?.let(::Play)
                "account" -> Account
                "stats" -> Stats
                "settings" -> Settings
                else -> null
            }
        }

        private val ID = Regex("[0-9a-f]{64}")
    }
}
