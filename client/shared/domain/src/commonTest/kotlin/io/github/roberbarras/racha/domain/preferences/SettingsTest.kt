package io.github.roberbarras.racha.domain.preferences

import io.github.roberbarras.racha.domain.library.Mode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class SettingsTest {
    @Test
    fun keysAreUniqueAndResolvable() {
        assertEquals(Settings.all.size, Settings.all.map { it.key }.toSet().size)
        Settings.all.forEach { assertSame(it, Settings.byKey(it.key)) }
    }

    @Test
    fun everyDefaultIsValidAndRoundTripsThroughItsEncoding() {
        Settings.all.forEach { roundTripDefault(it) }
    }

    private fun <T> roundTripDefault(setting: Setting<T>) {
        setting.validate(setting.default)
        assertEquals(setting.default, setting.decode(setting.encode(setting.default)), "round trip of ${setting.key}")
    }

    @Test
    fun specDefaults() {
        assertEquals(10, Settings.SeekStepSeconds.default)
        assertEquals(60, Settings.SaveIntervalSeconds.default)
        assertEquals(false, Settings.SubtitlesEnabled.default)
        assertEquals(true, Settings.AutoplayNext.default)
        assertEquals(5, Settings.SyncIntervalMinutes.default)
        assertEquals(false, Settings.SyncEnabled.default)
        assertEquals(listOf(0.25, 0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0, 3.0, 4.0), Settings.SpeedSteps.default)
        assertEquals(ConflictPolicy.ASK, Settings.SyncConflictPolicy.default)
        assertEquals(Mode.STUDY, Settings.Mode.default)
        assertEquals(false, Settings.HardwareDecoding.default)
    }

    @Test
    fun rejectsValuesOutsideTheAllowedSet() {
        assertFailsWith<IllegalArgumentException> { Settings.SeekStepSeconds.validate(7) }
        assertFailsWith<IllegalArgumentException> { Settings.SaveIntervalSeconds.validate(45) }
        assertFailsWith<IllegalArgumentException> { Settings.DefaultSpeed.validate(5.0) }
        assertFailsWith<IllegalArgumentException> { Settings.SpeedSteps.validate(listOf(2.0, 1.0)) }
        assertFailsWith<IllegalArgumentException> { Settings.DailyReminderTime.validate("25:00") }
    }

    @Test
    fun unreadableOrInvalidStoredValuesFallBackToTheDefault() {
        assertEquals(10, Settings.SeekStepSeconds.decodeOrDefault("7"))
        assertEquals(10, Settings.SeekStepSeconds.decodeOrDefault("ten"))
        assertEquals(ThemeMode.SYSTEM, Settings.Theme.decodeOrDefault(null))
        assertEquals(ThemeMode.DARK, Settings.Theme.decodeOrDefault("dark"))
    }
}
