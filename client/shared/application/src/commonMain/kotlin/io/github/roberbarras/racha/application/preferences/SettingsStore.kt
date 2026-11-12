package io.github.roberbarras.racha.application.preferences

import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

/** Outbound port: raw, encoded setting values keyed by [io.github.roberbarras.racha.domain.preferences.Setting.key]. */
interface SettingsStore {
    suspend fun get(key: String): String?

    suspend fun put(key: String, value: String, at: Instant)

    suspend fun remove(key: String)

    fun observe(key: String): Flow<String?>

    suspend fun all(): Map<String, String>
}
