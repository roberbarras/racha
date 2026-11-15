package io.github.roberbarras.racha.stats.application;

import io.github.roberbarras.racha.stats.domain.PersonalStats;
import io.github.roberbarras.racha.stats.domain.Streak;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

public final class StatsService {
    private final PersonalStats.Queries queries;
    private final Clock clock;

    public StatsService(PersonalStats.Queries queries, Clock clock) {
        this.queries = queries;
        this.clock = clock;
    }

    /** Days are counted in [zone] (the client's), so "today" matches the user's today. */
    public PersonalStats me(UUID userId, ZoneId zone) {
        var days = queries.activeDays(userId, zone);
        return new PersonalStats(
                queries.completedElements(userId), queries.elementsInProgress(userId), queries.historyEntries(userId),
                days.size(), Streak.days(days, LocalDate.now(clock.withZone(zone))), queries.watchSeconds(userId), queries.lastWatchedAt(userId));
    }
}
