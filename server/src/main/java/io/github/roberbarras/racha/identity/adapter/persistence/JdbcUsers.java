package io.github.roberbarras.racha.identity.adapter.persistence;

import io.github.roberbarras.racha.identity.domain.Device;
import io.github.roberbarras.racha.identity.domain.Email;
import io.github.roberbarras.racha.identity.domain.IdentityPorts;
import io.github.roberbarras.racha.identity.domain.LearningMode;
import io.github.roberbarras.racha.identity.domain.Platform;
import io.github.roberbarras.racha.identity.domain.RefreshSession;
import io.github.roberbarras.racha.identity.domain.Role;
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

/** Users in PostgreSQL. */
public final class JdbcUsers implements IdentityPorts.Users {
    private final JdbcClient jdbc;

    public JdbcUsers(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insert(User user, Instant now) {
        return jdbc.sql("""
                insert into users (id, email, password_hash, display_name, mode, created_at, updated_at, disabled, role)
                values (:id, :email, :hash, :name, :mode, :now, :now, false, :role)
                on conflict (email) do nothing""")
                .param("id", user.id()).param("email", user.email().value()).param("hash", user.passwordHash())
                .param("name", user.displayName()).param("mode", user.mode().wire()).param("now", Sql.time(now))
                .param("role", user.role().wire())
                .update() == 1;
    }

    @Override
    public void updatePassword(UUID id, String passwordHash, Instant now) {
        jdbc.sql("update users set password_hash = :hash, updated_at = :now where id = :id")
                .param("hash", passwordHash).param("now", Sql.time(now)).param("id", id).update();
    }

    @Override
    public boolean hasAdmin() {
        return jdbc.sql("select exists (select 1 from users where role = 'admin' and not disabled)").query(Boolean.class).single();
    }

    @Override
    public boolean promote(Email email, Instant now) {
        jdbc.sql("update users set role = 'admin', updated_at = :now where email = :email and role <> 'admin'")
                .param("now", Sql.time(now)).param("email", email.value()).update();
        return jdbc.sql("select exists (select 1 from users where email = :email)").param("email", email.value()).query(Boolean.class).single();
    }

    @Override
    public Optional<User> byEmail(Email email) {
        return jdbc.sql("select * from users where email = :email").param("email", email.value()).query(JdbcUsers::user).optional();
    }

    @Override
    public Optional<User> byId(UUID id) {
        return jdbc.sql("select * from users where id = :id").param("id", id).query(JdbcUsers::user).optional();
    }

    private static User user(ResultSet rs, int row) throws SQLException {
        return new User(rs.getObject("id", UUID.class), new Email(rs.getString("email")), rs.getString("password_hash"),
                rs.getString("display_name"), LearningMode.fromWire(rs.getString("mode")), Sql.instant(rs, "created_at"), rs.getBoolean("disabled"),
                Role.fromWire(rs.getString("role")));
    }

}
