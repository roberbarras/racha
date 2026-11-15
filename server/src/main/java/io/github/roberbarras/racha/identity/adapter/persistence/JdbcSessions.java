package io.github.roberbarras.racha.identity.adapter.persistence;

import io.github.roberbarras.racha.identity.domain.Device;
import io.github.roberbarras.racha.identity.domain.Email;
import io.github.roberbarras.racha.identity.domain.IdentityPorts;
import io.github.roberbarras.racha.identity.domain.LearningMode;
import io.github.roberbarras.racha.identity.domain.Platform;
import io.github.roberbarras.racha.identity.domain.RefreshSession;
import io.github.roberbarras.racha.identity.domain.User;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Sessions in PostgreSQL. */
public final class JdbcSessions implements IdentityPorts.Sessions {
    private final JdbcClient jdbc;

    public JdbcSessions(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(RefreshSession s) {
        jdbc.sql("""
                insert into sessions (id, user_id, device_id, family_id, token_hash, created_at, expires_at)
                values (:id, :user, :device, :family, :hash, :created, :expires)""")
                .param("id", s.id()).param("user", s.userId()).param("device", s.deviceId()).param("family", s.familyId())
                .param("hash", s.tokenHash()).param("created", Sql.time(s.createdAt())).param("expires", Sql.time(s.expiresAt()))
                .update();
    }

    @Override
    public Optional<RefreshSession> byTokenHash(byte[] hash) {
        return jdbc.sql("select * from sessions where token_hash = :hash").param("hash", hash).query(JdbcSessions::session).optional();
    }

    @Override
    public boolean markUsed(UUID id, Instant now) {
        return jdbc.sql("update sessions set used_at = :now where id = :id and used_at is null and revoked_at is null")
                .param("now", Sql.time(now)).param("id", id).update() == 1;
    }

    @Override
    public void revokeFamily(UUID familyId, Instant now) {
        jdbc.sql("update sessions set revoked_at = :now where family_id = :family and revoked_at is null")
                .param("now", Sql.time(now)).param("family", familyId).update();
    }

    private static RefreshSession session(ResultSet rs, int row) throws SQLException {
        return new RefreshSession(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class), rs.getObject("device_id", UUID.class),
                rs.getObject("family_id", UUID.class), rs.getBytes("token_hash"), Sql.instant(rs, "created_at"), Sql.instant(rs, "expires_at"),
                Sql.instant(rs, "used_at"), Sql.instant(rs, "revoked_at"));
    }

}
