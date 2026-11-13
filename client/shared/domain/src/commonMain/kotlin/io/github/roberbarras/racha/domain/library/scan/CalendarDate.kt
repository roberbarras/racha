package io.github.roberbarras.racha.domain.library.scan

/** A validated calendar date found in a file or folder name (no time zone involved). */
data class CalendarDate(val year: Int, val month: Int, val day: Int) : Comparable<CalendarDate> {
    init {
        require(year in 1900..2999 && month in 1..12 && day in 1..daysIn(year, month)) { "Invalid date $year-$month-$day" }
    }

    override fun compareTo(other: CalendarDate): Int = compareValuesBy(this, other, { it.year }, { it.month }, { it.day })

    /** ISO 8601, e.g. `2026-11-13`. */
    override fun toString(): String =
        "${year.toString().padStart(4, '0')}-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"

    /** Days since 1970-01-01 (proleptic Gregorian). */
    fun toEpochDay(): Long {
        val y = if (month <= 2) year - 1L else year.toLong()
        val era = y.floorDiv(400L)
        val yoe = y - era * 400
        val mp = (month + 9) % 12
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097 + doe - 719_468
    }

    fun plusDays(days: Long): CalendarDate = ofEpochDay(toEpochDay() + days)

    companion object {
        /** Inverse of [toEpochDay] (H. Hinnant's civil-from-days). */
        fun ofEpochDay(epochDay: Long): CalendarDate {
            val z = epochDay + 719_468
            val era = z.floorDiv(146_097L)
            val doe = z - era * 146_097
            val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
            val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
            val mp = (5 * doy + 2) / 153
            val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
            val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
            return CalendarDate((yoe + era * 400 + if (m <= 2) 1 else 0).toInt(), m, d)
        }

        fun orNull(year: Int, month: Int, day: Int): CalendarDate? =
            runCatching { CalendarDate(year, month, day) }.getOrNull()

        private fun daysIn(year: Int, month: Int): Int = when (month) {
            2 -> if ((year % 4 == 0 && year % 100 != 0) || year % 400 == 0) 29 else 28
            4, 6, 9, 11 -> 30
            else -> 31
        }
    }
}
