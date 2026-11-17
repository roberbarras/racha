package io.github.roberbarras.racha.identity.domain;

import java.time.Instant;
import java.util.UUID;

public record User(UUID id, Email email, String passwordHash, String displayName, LearningMode mode, Instant createdAt, boolean disabled, Role role) {
    public boolean isAdmin() {
        return role == Role.ADMIN;
    }
}
