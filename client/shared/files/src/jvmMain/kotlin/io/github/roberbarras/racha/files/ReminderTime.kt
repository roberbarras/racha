package io.github.roberbarras.racha.files

import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime

/** When the daily reminder (§9, "HH:MM" local time) is next due. */
object ReminderTime {
    private val DEFAULT = LocalTime.of(19, 0)

    /** From [now] to the next [time], today or tomorrow. */
    fun untilNext(time: String, now: ZonedDateTime = ZonedDateTime.now()): Duration {
        val at = runCatching { LocalTime.parse(time) }.getOrDefault(DEFAULT)
        var next = now.with(at).withSecond(0).withNano(0)
        if (!next.isAfter(now)) next = next.plusDays(1)
        return Duration.between(now, next)
    }
}
