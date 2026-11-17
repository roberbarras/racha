package io.github.roberbarras.racha.stats.application;

import io.github.roberbarras.racha.stats.domain.PersonalStats;
import io.github.roberbarras.racha.stats.domain.StatsDetails;
import io.github.roberbarras.racha.stats.domain.Streak;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

public final class StatsService {
    private final PersonalStats.Queries queries;
    private final StatsDetails.Queries details;
    private final Clock clock;

    public StatsService(PersonalStats.Queries queries, StatsDetails.Queries details, Clock clock) {
        this.queries = queries;
        this.details = details;
        this.clock = clock;
    }

    /** The web panel's breakdowns (H14): sessions, best streak, hours, weekdays, categories, devices, titles, recent. */
    public StatsDetails details(UUID userId, ZoneId zone) {
        return new StatsDetails(details.sessions(userId), details.bestStreak(userId), details.byHour(userId, zone), details.byWeekday(userId, zone),
                details.byCategory(userId), details.byDevice(userId), details.titles(userId, 30), details.recent(userId, 20));
    }

    /** Days in the per-day breakdown: a year of weeks for the activity heat map. */
    public static final int DAYS = 371;

    /** Days are counted in [zone] (the client's), so "today" matches the user's today. */
    public PersonalStats me(UUID userId, ZoneId zone) {
        var days = queries.activeDays(userId, zone);
        var today = LocalDate.now(clock.withZone(zone));
        return new PersonalStats(
                queries.completedElements(userId), queries.elementsInProgress(userId), queries.historyEntries(userId),
                days.size(), Streak.days(days, today), queries.watchSeconds(userId), queries.lastWatchedAt(userId),
                queries.watchByDay(userId, zone, today.minusDays(DAYS - 1)), queries.watchByCategory(userId));
    }
}
