package io.github.roberbarras.racha.identity.domain;

import java.time.Instant;
import java.util.UUID;

/** A recovery code sent to a user; only its hash is stored. Valid once, until [expiresAt]. */
public record PasswordReset(UUID id, UUID userId, byte[] codeHash, Instant createdAt, Instant expiresAt, Instant usedAt) {
    public boolean usableAt(Instant now) {
        return usedAt == null && now.isBefore(expiresAt);
    }
}
