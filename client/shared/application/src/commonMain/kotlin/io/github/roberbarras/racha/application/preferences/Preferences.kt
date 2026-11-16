package io.github.roberbarras.racha.application.preferences

import io.github.roberbarras.racha.application.shared.Clock
import io.github.roberbarras.racha.domain.preferences.Setting
import io.github.roberbarras.racha.domain.preferences.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Typed access to settings: validates on write, falls back to defaults on unreadable stored values. */
class Preferences(private val store: SettingsStore, private val clock: Clock) {

    suspend fun <T> get(setting: Setting<T>): T = setting.decodeOrDefault(store.get(setting.key))

    /** @throws IllegalArgumentException if [value] is not allowed for [setting]. */
    suspend fun <T> set(setting: Setting<T>, value: T) {
        setting.validate(value)
        store.put(setting.key, setting.encode(value), clock.now())
    }

    /** Back to the default; the stored row is removed so a future default change applies. */
    suspend fun reset(setting: Setting<*>) = store.remove(setting.key)

    fun <T> observe(setting: Setting<T>): Flow<T> =
        store.observe(setting.key).map(setting::decodeOrDefault).distinctUntilChanged()

    /** Values the user changed (stored), by key, for known settings only. */
    suspend fun changed(): Map<String, String> = store.all().filterKeys { Settings.byKey(it) != null }

    /** Stores [raw] for [key] if it is a known setting and a valid value; false otherwise (import). */
    suspend fun setEncoded(key: String, raw: String): Boolean {
        val setting = Settings.byKey(key) ?: return false
        if (!setting.accepts(raw)) return false
        store.put(key, raw, clock.now())
        return true
    }
}
