package io.github.roberbarras.racha.ui.app

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
data class PlayerRoute(val elementId: String, val fromStart: Boolean = false)
