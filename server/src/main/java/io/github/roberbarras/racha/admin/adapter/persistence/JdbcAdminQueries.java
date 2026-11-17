package io.github.roberbarras.racha.admin.adapter.persistence;

import io.github.roberbarras.racha.admin.domain.AdminPorts;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/** The admin screen's figures, straight from PostgreSQL. */
public final class JdbcAdminQueries implements AdminPorts.Queries {
    private static final Duration ACTIVE = Duration.ofDays(30);
    private static final Duration RECENT = Duration.ofDays(7);

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    public JdbcAdminQueries(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @Override
    public AdminPorts.Counts counts(Instant now) {
        var active = time(now.minus(ACTIVE));
        var recent = time(now.minus(RECENT));
        return jdbc.sql("""
                select
                  (select count(*) from users) as users,
                  (select count(*) from users where role = 'admin' and not disabled) as admins,
                  (select count(*) from users where disabled) as disabled_users,
                  (select count(distinct user_id) from devices where last_seen_at >= :active) as active_users,
                  (select count(*) from devices) as devices,
                  (select count(*) from devices where last_seen_at >= :active) as active_devices,
                  (select count(*) from sessions where revoked_at is null and used_at is null and expires_at > :now) as active_sessions,
                  (select count(*) from progress where not deleted) as progress_rows,
                  (select count(*) from history where not deleted) as history_rows,
                  (select count(*) from analytics_events where received_at >= :recent) as recent_events,
                  (select count(*) from errors where received_at >= :recent) as recent_errors,
                  pg_database_size(current_database()) as database_bytes""")
                .param("active", active).param("recent", recent).param("now", time(now))
                .query((rs, row) -> new AdminPorts.Counts(
                        rs.getLong("users"), rs.getLong("admins"), rs.getLong("disabled_users"), rs.getLong("active_users"),
                        rs.getLong("devices"), rs.getLong("active_devices"), rs.getLong("active_sessions"), rs.getLong("progress_rows"),
                        rs.getLong("history_rows"), rs.getLong("recent_events"), rs.getLong("recent_errors"), rs.getLong("database_bytes")))
                .single();
    }

    @Override
    public List<AdminPorts.UserSummary> users(Instant now) {
        return jdbc.sql("""
                select u.id, u.email, u.display_name, u.role, u.disabled, u.created_at,
                       (select max(d.last_seen_at) from devices d where d.user_id = u.id) as last_seen_at,
                       (select count(*) from devices d where d.user_id = u.id) as devices,
                       (select count(*) from sessions s where s.user_id = u.id and s.revoked_at is null and s.used_at is null
                                                       and s.expires_at > :now) as active_sessions
                from users u order by u.created_at, u.id""")
                .param("now", time(now))
                .query((rs, row) -> new AdminPorts.UserSummary(
                        rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("display_name"), rs.getString("role"),
                        rs.getBoolean("disabled"), instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("last_seen_at")),
                        rs.getInt("devices"), rs.getInt("active_sessions")))
                .list();
    }

    @Override
    public void setRole(UUID userId, String role, Instant now) {
        jdbc.sql("update users set role = :role, updated_at = :now where id = :id")
                .param("role", role).param("now", time(now)).param("id", userId).update();
    }

    @Override
    public void setDisabled(UUID userId, boolean disabled, Instant now) {
        transactions.executeWithoutResult(status -> {
            jdbc.sql("update users set disabled = :disabled, updated_at = :now where id = :id")
                    .param("disabled", disabled).param("now", time(now)).param("id", userId).update();
            if (disabled) {
                jdbc.sql("update sessions set revoked_at = :now where user_id = :id and revoked_at is null")
                        .param("now", time(now)).param("id", userId).update();
            }
        });
    }

    private static OffsetDateTime time(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
