package io.github.roberbarras.racha.domain.learning

import io.github.roberbarras.racha.domain.library.scan.CalendarDate

/** Consecutive local days with viewing activity (spec §2.2 "rachas", §10.1 motivation). */
object Streak {
    /**
     * Length of the run ending today, or ending yesterday when nothing was watched yet today (the streak is
     * still alive until the day is over).
     */
    fun current(activeDays: Set<CalendarDate>, today: CalendarDate): Int {
        var day = if (today in activeDays) today else today.plusDays(-1)
        var length = 0
        while (day in activeDays) {
            length++
            day = day.plusDays(-1)
        }
        return length
    }

    /** Longest run of consecutive active days ever. */
    fun longest(activeDays: Set<CalendarDate>): Int {
        val days = activeDays.map { it.toEpochDay() }.sorted()
        var best = 0
        var run = 0
        var previous: Long? = null
        for (day in days) {
            run = if (previous != null && day == previous + 1) run + 1 else 1
            best = maxOf(best, run)
            previous = day
        }
        return best
    }
}
