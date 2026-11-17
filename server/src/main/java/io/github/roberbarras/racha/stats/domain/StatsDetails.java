package io.github.roberbarras.racha.stats.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * Everything the web panel shows about one user's watching (H14), beyond {@link PersonalStats}. Hours and weekdays
 * are in the user's zone; watch time comes from the apps' "watch" events (§8), titles from the synced library.
 */
public record StatsDetails(
        Sessions sessions, Streak bestStreak, List<HourWatch> byHour, List<WeekdayWatch> byWeekday, List<CategoryWatch> byCategory,
        List<DeviceWatch> byDevice, List<TitleProgress> titles, List<RecentWatch> recent) {

    /** Viewing sessions: how many, how long on average, at what speed, how often with subtitles (0–1). */
    public record Sessions(long count, long averageSeconds, double averageSpeed, double withSubtitles) {}

    public record Streak(int days, LocalDate from, LocalDate to) {}

    /** [hour] 0–23. */
    public record HourWatch(int hour, long seconds) {}

    /** [day] 1 = Monday … 7 = Sunday (ISO). */
    public record WeekdayWatch(int day, long seconds) {}

    public record CategoryWatch(String categoryId, String name, long seconds) {}

    public record DeviceWatch(UUID deviceId, String name, String platform, long seconds) {}

    public record TitleProgress(String itemId, String title, String categoryId, long completed, long elements, long seconds, Instant lastWatchedAt) {}

    public record RecentWatch(Instant at, String element, String title, boolean completed) {}

    public interface Queries {
        Sessions sessions(UUID userId);

        Streak bestStreak(UUID userId);

        List<HourWatch> byHour(UUID userId, ZoneId zone);

        List<WeekdayWatch> byWeekday(UUID userId, ZoneId zone);

        List<CategoryWatch> byCategory(UUID userId);

        List<DeviceWatch> byDevice(UUID userId);

        /** The [limit] titles watched most recently. */
        List<TitleProgress> titles(UUID userId, int limit);

        List<RecentWatch> recent(UUID userId, int limit);
    }
}
