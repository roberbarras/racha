package io.github.roberbarras.racha.stats.adapter.persistence;

import io.github.roberbarras.racha.stats.domain.PersonalStats;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class JdbcStatsQueries implements PersonalStats.Queries {
    private final JdbcClient jdbc;

    public JdbcStatsQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long completedElements(UUID userId) {
        return count("select count(*) from progress where user_id = :user and not deleted and completed", userId);
    }

    @Override
    public long elementsInProgress(UUID userId) {
        return count("select count(*) from progress where user_id = :user and not deleted and not completed and position_sec > 0", userId);
    }

    @Override
    public long historyEntries(UUID userId) {
        return count("select count(*) from history where user_id = :user and not deleted", userId);
    }

    @Override
    public Set<LocalDate> activeDays(UUID userId, ZoneId zone) {
        return new HashSet<>(jdbc.sql("""
                select distinct (watched_at at time zone :zone)::date as day from history
                where user_id = :user and not deleted and watched_at is not null""")
                .param("zone", zone.getId()).param("user", userId)
                .query((rs, row) -> rs.getObject("day", LocalDate.class)).list());
    }

    @Override
    public long watchSeconds(UUID userId) {
        return count("""
                select coalesce(sum((properties ->> 'seconds')::bigint), 0) from analytics_events
                where user_id = :user and type = 'watch' and jsonb_typeof(properties -> 'seconds') = 'number'""", userId);
    }

    @Override
    public Instant lastWatchedAt(UUID userId) {
        return jdbc.sql("select max(watched_at) from history where user_id = :user and not deleted").param("user", userId)
                .query((rs, row) -> {
                    Timestamp t = rs.getTimestamp(1);
                    return t == null ? null : t.toInstant();
                }).optional().orElse(null);
    }

    private long count(String sql, UUID userId) {
        return jdbc.sql(sql).param("user", userId).query(Long.class).single();
    }
}
