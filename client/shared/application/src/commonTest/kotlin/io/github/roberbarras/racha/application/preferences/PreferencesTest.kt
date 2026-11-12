package io.github.roberbarras.racha.application.preferences

import io.github.roberbarras.racha.application.shared.Clock
import io.github.roberbarras.racha.domain.preferences.Settings
import io.github.roberbarras.racha.domain.preferences.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Instant

class PreferencesTest {
    private val now = Instant.parse("2026-11-12T10:00:00Z")
    private val store = InMemorySettingsStore()
    private val preferences = Preferences(store, Clock { now })

    @Test
    fun returnsDefaultsUntilSet() = runTest {
        assertEquals(10, preferences.get(Settings.SeekStepSeconds))

        preferences.set(Settings.SeekStepSeconds, 30)

        assertEquals(30, preferences.get(Settings.SeekStepSeconds))
        assertEquals("30" to now, store.values["player.seekStepSeconds"])
    }

    @Test
    fun rejectsInvalidValuesWithoutWriting() = runTest {
        assertFailsWith<IllegalArgumentException> { preferences.set(Settings.SaveIntervalSeconds, 45) }
        assertNull(store.values["progress.saveIntervalSeconds"])
    }

    @Test
    fun corruptedStoredValueFallsBackToDefault() = runTest {
        store.put("general.theme", "purple", now)

        assertEquals(ThemeMode.SYSTEM, preferences.get(Settings.Theme))
    }

    @Test
    fun resetRestoresTheDefault() = runTest {
        preferences.set(Settings.AutoplayNext, false)
        preferences.reset(Settings.AutoplayNext)

        assertEquals(true, preferences.get(Settings.AutoplayNext))
    }

    @Test
    fun observeEmitsTypedDistinctValues() = runTest {
        val seen = mutableListOf<ThemeMode>()
        val job = launch { preferences.observe(Settings.Theme).take(2).toList(seen) }
        yield()

        preferences.set(Settings.Theme, ThemeMode.SYSTEM) // same as default: not re-emitted
        preferences.set(Settings.Theme, ThemeMode.DARK)
        job.join()

        assertEquals(listOf(ThemeMode.SYSTEM, ThemeMode.DARK), seen)
    }
}

class InMemorySettingsStore : SettingsStore {
    private val state = MutableStateFlow<Map<String, Pair<String, Instant>>>(emptyMap())
    val values: Map<String, Pair<String, Instant>> get() = state.value

    override suspend fun get(key: String) = state.value[key]?.first

    override suspend fun put(key: String, value: String, at: Instant) {
        state.value = state.value + (key to (value to at))
    }

    override suspend fun remove(key: String) {
        state.value = state.value - key
    }

    override fun observe(key: String): Flow<String?> = state.map { it[key]?.first }

    override suspend fun all() = state.value.mapValues { it.value.first }

    suspend fun current(key: String) = state.map { it[key] }.first()
}
