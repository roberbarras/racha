package io.github.roberbarras.racha.files

import io.github.roberbarras.racha.application.library.scan.LocalDates
import io.github.roberbarras.racha.domain.library.scan.CalendarDate
import java.time.ZoneId
import kotlin.time.Instant

/** Calendar dates in the device's current time zone (java.time, available on Android since API 26). */
class SystemLocalDates(private val zone: () -> ZoneId = ZoneId::systemDefault) : LocalDates {
    override fun dateOf(instant: Instant): CalendarDate {
        val date = java.time.Instant.ofEpochMilli(instant.toEpochMilliseconds()).atZone(zone()).toLocalDate()
        return CalendarDate(date.year, date.monthValue, date.dayOfMonth)
    }
}
