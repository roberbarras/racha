package io.github.roberbarras.racha.identity.domain;

import java.time.Instant;
import java.util.UUID;

/** A phone or computer of a user (§7.3 /devices/register); per-device settings and sessions hang from it. */
public record Device(UUID id, UUID userId, String name, Platform platform, String appVersion, Instant createdAt, Instant lastSeenAt) {
    public static final int MAX_NAME_LENGTH = 100;
}
