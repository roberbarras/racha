package io.github.roberbarras.racha.stats.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** §7.3 `/stats/me`. Watch time comes from the clients' "watch" events (§8). */
public record PersonalStats(
        long completedElements, long elementsInProgress, long historyEntries, int activeDays, int streakDays,
        long watchSeconds, Instant lastWatchedAt, List<DayWatch> watchByDay, List<CategoryWatch> watchByCategory) {

    /** Seconds watched on [day] (in the caller's zone), all devices. */
    public record DayWatch(LocalDate day, long seconds) {}

    public record CategoryWatch(String categoryId, long seconds) {}

    public interface Queries {
        long completedElements(UUID userId);

        long elementsInProgress(UUID userId);

        long historyEntries(UUID userId);

        Set<LocalDate> activeDays(UUID userId, ZoneId zone);

        long watchSeconds(UUID userId);

        Instant lastWatchedAt(UUID userId);

        /** Days since [from] (inclusive) with watch time, oldest first. */
        List<DayWatch> watchByDay(UUID userId, ZoneId zone, LocalDate from);

        List<CategoryWatch> watchByCategory(UUID userId);
    }
}
