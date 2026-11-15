package io.github.roberbarras.racha.stats.domain;

import java.time.LocalDate;
import java.util.Set;

/** Consecutive days with activity up to today; a day without activity yet today does not break it until tomorrow. */
public final class Streak {
    private Streak() {}

    public static int days(Set<LocalDate> active, LocalDate today) {
        var day = active.contains(today) ? today : today.minusDays(1);
        int streak = 0;
        while (active.contains(day)) {
            streak++;
            day = day.minusDays(1);
        }
        return streak;
    }
}
