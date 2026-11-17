package io.github.roberbarras.racha.identity.adapter.persistence;

import io.github.roberbarras.racha.identity.domain.IdentityPorts;
import io.github.roberbarras.racha.identity.domain.PasswordReset;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/** Recovery codes in PostgreSQL (their SHA-256 only). */
public final class JdbcPasswordResets implements IdentityPorts.PasswordResets {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    public JdbcPasswordResets(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @Override
    public void replace(PasswordReset reset, String requestedFrom) {
        transactions.executeWithoutResult(status -> {
            jdbc.sql("update password_resets set used_at = :now where user_id = :user and used_at is null")
                    .param("now", Sql.time(reset.createdAt())).param("user", reset.userId()).update();
            jdbc.sql("""
                    insert into password_resets (id, user_id, code_hash, created_at, expires_at, requested_from)
                    values (:id, :user, :hash, :created, :expires, :from)""")
                    .param("id", reset.id()).param("user", reset.userId()).param("hash", reset.codeHash())
                    .param("created", Sql.time(reset.createdAt())).param("expires", Sql.time(reset.expiresAt())).param("from", requestedFrom)
                    .update();
        });
    }

    @Override
    public Optional<PasswordReset> latestOf(UUID userId) {
        return jdbc.sql("select * from password_resets where user_id = :user order by created_at desc limit 1")
                .param("user", userId)
                .query((rs, row) -> new PasswordReset(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class), rs.getBytes("code_hash"),
                        Sql.instant(rs, "created_at"), Sql.instant(rs, "expires_at"), Sql.instant(rs, "used_at")))
                .optional();
    }

    @Override
    public boolean markUsed(UUID id, Instant now) {
        return jdbc.sql("update password_resets set used_at = :now where id = :id and used_at is null")
                .param("now", Sql.time(now)).param("id", id).update() == 1;
    }
}
