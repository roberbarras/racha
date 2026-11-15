package io.github.roberbarras.racha.identity.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One refresh token (stored as its hash). Tokens rotate: using one marks it used and issues the next in the same
 * family. A used token presented again means it was copied: the whole family is revoked (RFC 6819 §5.2.2.3).
 */
public record RefreshSession(
        UUID id, UUID userId, UUID deviceId, UUID familyId, byte[] tokenHash,
        Instant createdAt, Instant expiresAt, Instant usedAt, Instant revokedAt) {

    public enum State { ACTIVE, EXPIRED, USED, REVOKED }

    public State stateAt(Instant now) {
        if (revokedAt != null) return State.REVOKED;
        if (usedAt != null) return State.USED;
        if (!now.isBefore(expiresAt)) return State.EXPIRED;
        return State.ACTIVE;
    }
}
