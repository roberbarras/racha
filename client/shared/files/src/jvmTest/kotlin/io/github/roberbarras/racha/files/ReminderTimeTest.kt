package io.github.roberbarras.racha.files

import java.time.Duration
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

class ReminderTimeTest {
    private val evening = ZonedDateTime.parse("2026-11-16T18:30:15+01:00[Europe/Madrid]")

    @Test
    fun nextIsTodayOrTomorrow() {
        assertEquals(Duration.ofMinutes(30).minusSeconds(15), ReminderTime.untilNext("19:00", evening))
        assertEquals(Duration.ofHours(24).minusMinutes(30).minusSeconds(15), ReminderTime.untilNext("18:00", evening).plusMinutes(0))
        assertEquals(ReminderTime.untilNext("19:00", evening), ReminderTime.untilNext("not a time", evening), "falls back to 19:00")
    }
}
