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

    companion object {
        fun orNull(year: Int, month: Int, day: Int): CalendarDate? =
            runCatching { CalendarDate(year, month, day) }.getOrNull()

        private fun daysIn(year: Int, month: Int): Int = when (month) {
            2 -> if ((year % 4 == 0 && year % 100 != 0) || year % 400 == 0) 29 else 28
            4, 6, 9, 11 -> 30
            else -> 31
        }
    }
}
