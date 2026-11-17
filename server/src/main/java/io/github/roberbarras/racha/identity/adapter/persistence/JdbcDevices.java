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

/** Devices in PostgreSQL. */
public final class JdbcDevices implements IdentityPorts.Devices {
    private final JdbcClient jdbc;

    public JdbcDevices(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void upsert(Device device) {
        jdbc.sql("""
                insert into devices (id, user_id, name, platform, app_version, created_at, last_seen_at)
                values (:id, :user, :name, :platform, :version, :created, :seen)
                on conflict (id) do update set name = excluded.name, platform = excluded.platform,
                    app_version = excluded.app_version, last_seen_at = excluded.last_seen_at""")
                .param("id", device.id()).param("user", device.userId()).param("name", device.name())
                .param("platform", device.platform().wire()).param("version", device.appVersion())
                .param("created", Sql.time(device.createdAt())).param("seen", Sql.time(device.lastSeenAt()))
                .update();
    }

    @Override
    public Optional<Device> byId(UUID id) {
        return jdbc.sql("select * from devices where id = :id").param("id", id).query(JdbcDevices::device).optional();
    }

    @Override
    public List<Device> ofUser(UUID userId) {
        return jdbc.sql("select * from devices where user_id = :user order by created_at").param("user", userId).query(JdbcDevices::device).list();
    }

    @Override
    public void touch(UUID id, Instant now) {
        jdbc.sql("update devices set last_seen_at = :now where id = :id").param("now", Sql.time(now)).param("id", id).update();
    }

    @Override
    public boolean delete(UUID userId, UUID deviceId) {
        // Its sessions go with it (on delete cascade).
        return jdbc.sql("delete from devices where id = :id and user_id = :user").param("id", deviceId).param("user", userId).update() == 1;
    }

    private static Device device(ResultSet rs, int row) throws SQLException {
        return new Device(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class), rs.getString("name"),
                Platform.fromWire(rs.getString("platform")), rs.getString("app_version"), Sql.instant(rs, "created_at"), Sql.instant(rs, "last_seen_at"));
    }

}
