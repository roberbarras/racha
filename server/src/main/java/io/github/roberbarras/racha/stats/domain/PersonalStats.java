package io.github.roberbarras.racha.stats.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;
import java.util.UUID;

/** §7.3 `/stats/me`. Watch time comes from the clients' "watch" events (§8). */
public record PersonalStats(
        long completedElements, long elementsInProgress, long historyEntries, int activeDays, int streakDays,
        long watchSeconds, Instant lastWatchedAt) {

    public interface Queries {
        long completedElements(UUID userId);

        long elementsInProgress(UUID userId);

        long historyEntries(UUID userId);

        Set<LocalDate> activeDays(UUID userId, ZoneId zone);

        long watchSeconds(UUID userId);

        Instant lastWatchedAt(UUID userId);
    }
}
