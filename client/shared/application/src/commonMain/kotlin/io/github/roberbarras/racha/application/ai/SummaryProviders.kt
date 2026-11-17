package io.github.roberbarras.racha.application.ai

import io.github.roberbarras.racha.application.preferences.Preferences
import io.github.roberbarras.racha.domain.ai.ProviderPreset
import io.github.roberbarras.racha.domain.ai.SummaryProvider
import io.github.roberbarras.racha.domain.preferences.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The user's summary providers (Settings → "Resúmenes con IA"): added from presets, edited, reordered (the order is the
 * order they are tried in), switched on and off, removed. The list is a per-device setting; each key goes to the
 * keyring under the provider's id and is never shown back, only whether there is one.
 */
class SummaryProviders(private val preferences: Preferences, private val keys: ProviderKeys) {
    fun observe(): Flow<List<SummaryProvider>> = preferences.observe(Settings.SummaryProviders).map(SummaryProvider::decode)

    suspend fun list(): List<SummaryProvider> = SummaryProvider.decode(preferences.get(Settings.SummaryProviders))

    /** A new provider from [preset], last in the list; its id is the preset's ("groq", then "groq-2"…). */
    suspend fun add(preset: ProviderPreset): SummaryProvider {
        val current = list()
        val id = generateSequence(1) { it + 1 }.map { if (it == 1) preset.id else "${preset.id}-$it" }.first { id -> current.none { it.id == id } }
        val provider = SummaryProvider(id, preset.id, preset.name, preset.baseUrl, preset.model)
        save(current + provider)
        return provider
    }

    suspend fun update(provider: SummaryProvider) = save(list().map { if (it.id == provider.id) provider else it })

    /** Moves the provider [by] places (−1 up, +1 down). */
    suspend fun move(id: String, by: Int) {
        val current = list().toMutableList()
        val from = current.indexOfFirst { it.id == id }
        if (from < 0) return
        val to = (from + by).coerceIn(0, current.lastIndex)
        current.add(to, current.removeAt(from))
        save(current)
    }

    suspend fun remove(id: String) {
        keys.of(id).clear()
        save(list().filterNot { it.id == id })
    }

    suspend fun hasKey(id: String): Boolean = !keys.of(id).read().isNullOrBlank()

    suspend fun setKey(id: String, key: String) = if (key.isBlank()) keys.of(id).clear() else keys.of(id).write(key.trim())

    private suspend fun save(list: List<SummaryProvider>) = preferences.set(Settings.SummaryProviders, SummaryProvider.encode(list))
}
