package io.github.roberbarras.racha.stats.adapter.persistence;

import io.github.roberbarras.racha.stats.domain.StatsDetails;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The web panel's breakdowns, over the `stats_*` views (V5) and the synced library. */
public final class JdbcStatsDetails implements StatsDetails.Queries {
    /** An element's or item's title: the user's edit if any. */
    private static final String TITLE = "coalesce(nullif(%1$s.data ->> 'titleOverride', ''), %1$s.data ->> 'title')";

    private final JdbcClient jdbc;

    public JdbcStatsDetails(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public StatsDetails.Sessions sessions(UUID userId) {
        return jdbc.sql("""
                select count(*) as n, coalesce(avg(seconds), 0) as avg_seconds,
                       coalesce(avg(playback_speed) filter (where playback_speed > 0), 1) as avg_speed,
                       coalesce(avg(case when coalesce(subtitles_lang, '') <> '' then 1.0 else 0.0 end), 0) as with_subtitles
                from stats_watch where user_id = :user""")
                .param("user", userId)
                .query((rs, row) -> new StatsDetails.Sessions(rs.getLong("n"), Math.round(rs.getDouble("avg_seconds")),
                        round(rs.getDouble("avg_speed")), round(rs.getDouble("with_subtitles"))))
                .single();
    }

    @Override
    public StatsDetails.Streak bestStreak(UUID userId) {
        return jdbc.sql("select days, started, ended from stats_streaks where user_id = :user order by days desc, ended desc limit 1")
                .param("user", userId)
                .query((rs, row) -> new StatsDetails.Streak(rs.getInt("days"), rs.getDate("started").toLocalDate(), rs.getDate("ended").toLocalDate()))
                .optional().orElse(new StatsDetails.Streak(0, null, null));
    }

    @Override
    public List<StatsDetails.HourWatch> byHour(UUID userId, ZoneId zone) {
        return jdbc.sql("""
                select extract(hour from coalesce(started_at, occurred_at) at time zone :zone)::int as hour, sum(seconds) as seconds
                from stats_watch where user_id = :user group by 1 order by 1""")
                .param("user", userId).param("zone", zone.getId())
                .query((rs, row) -> new StatsDetails.HourWatch(rs.getInt("hour"), rs.getLong("seconds"))).list();
    }

    @Override
    public List<StatsDetails.WeekdayWatch> byWeekday(UUID userId, ZoneId zone) {
        return jdbc.sql("""
                select extract(isodow from coalesce(started_at, occurred_at) at time zone :zone)::int as day, sum(seconds) as seconds
                from stats_watch where user_id = :user group by 1 order by 1""")
                .param("user", userId).param("zone", zone.getId())
                .query((rs, row) -> new StatsDetails.WeekdayWatch(rs.getInt("day"), rs.getLong("seconds"))).list();
    }

    @Override
    public List<StatsDetails.CategoryWatch> byCategory(UUID userId) {
        return jdbc.sql("""
                select w.category_id, c.data ->> 'name' as name, sum(w.seconds) as seconds
                from stats_watch w left join categories c on c.user_id = w.user_id and c.id = w.category_id
                where w.user_id = :user group by w.category_id, c.data ->> 'name' order by seconds desc""")
                .param("user", userId)
                .query((rs, row) -> new StatsDetails.CategoryWatch(rs.getString("category_id"), rs.getString("name"), rs.getLong("seconds"))).list();
    }

    @Override
    public List<StatsDetails.DeviceWatch> byDevice(UUID userId) {
        return jdbc.sql("""
                select w.device_id, d.name, d.platform, sum(w.seconds) as seconds
                from stats_watch w left join devices d on d.id = w.device_id
                where w.user_id = :user group by w.device_id, d.name, d.platform order by seconds desc""")
                .param("user", userId)
                .query((rs, row) -> new StatsDetails.DeviceWatch(rs.getObject("device_id", UUID.class), rs.getString("name"), rs.getString("platform"),
                        rs.getLong("seconds")))
                .list();
    }

    @Override
    public List<StatsDetails.TitleProgress> titles(UUID userId, int limit) {
        return jdbc.sql("""
                select t.item_id, %s as title, i.category_id, t.completed_elements, t.elements, t.last_watched_at,
                       coalesce((select sum(w.seconds) from stats_watch w where w.user_id = t.user_id and w.item_id = t.item_id), 0) as seconds
                from stats_titles t join library_items i on i.user_id = t.user_id and i.id = t.item_id
                where t.user_id = :user
                order by t.last_watched_at desc nulls last limit :limit""".formatted(TITLE.formatted("i")))
                .param("user", userId).param("limit", limit)
                .query((rs, row) -> new StatsDetails.TitleProgress(rs.getString("item_id"), rs.getString("title"), rs.getString("category_id"),
                        rs.getLong("completed_elements"), rs.getLong("elements"), rs.getLong("seconds"), instant(rs.getTimestamp("last_watched_at"))))
                .list();
    }

    @Override
    public List<StatsDetails.RecentWatch> recent(UUID userId, int limit) {
        return jdbc.sql("""
                select h.watched_at, %s as element, %s as title, coalesce(p.completed, false) as completed
                from history h
                left join playable_elements e on e.user_id = h.user_id and e.id = h.element_id
                left join library_items i on i.user_id = e.user_id and i.id = e.item_id
                left join progress p on p.user_id = h.user_id and p.id = h.element_id and not p.deleted
                where h.user_id = :user and not h.deleted and h.watched_at is not null
                order by h.watched_at desc limit :limit""".formatted(TITLE.formatted("e"), TITLE.formatted("i")))
                .param("user", userId).param("limit", limit)
                .query((rs, row) -> new StatsDetails.RecentWatch(instant(rs.getTimestamp("watched_at")), rs.getString("element"), rs.getString("title"),
                        rs.getBoolean("completed")))
                .list();
    }

    private static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
