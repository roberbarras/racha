package io.github.roberbarras.racha.ui.app

import io.github.roberbarras.racha.domain.playback.StartMode
import kotlinx.serialization.Serializable

@Serializable
data object HomeRoute

@Serializable
data class CategoryRoute(val key: String)

@Serializable
data class ItemRoute(val id: String)

@Serializable
data object MyListRoute

@Serializable
data object HistoryRoute

@Serializable
data object SearchRoute

@Serializable
data object FoldersRoute

@Serializable
data class PlayerRoute(val elementId: String, private val start: String = StartMode.ASK.name) {
    constructor(elementId: String, mode: StartMode) : this(elementId, mode.name)

    /** Stored by name: typed routes have no built-in NavType for enums. */
    val mode: StartMode get() = StartMode.valueOf(start)
}

/** Resource viewer (HTML/URL/TXT/PDF), H7. */
@Serializable
data class ResourceRoute(val elementId: String)
