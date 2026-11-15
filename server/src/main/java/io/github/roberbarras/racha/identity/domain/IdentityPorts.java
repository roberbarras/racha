package io.github.roberbarras.racha.identity.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Outbound ports of the identity context. */
public final class IdentityPorts {
    private IdentityPorts() {}

    public interface Users {
        /** False if the email is already taken. */
        boolean insert(User user, Instant now);

        Optional<User> byEmail(Email email);

        Optional<User> byId(UUID id);
    }

    public interface Devices {
        void upsert(Device device);

        Optional<Device> byId(UUID id);

        List<Device> ofUser(UUID userId);

        void touch(UUID id, Instant now);
    }

    public interface Sessions {
        void insert(RefreshSession session);

        Optional<RefreshSession> byTokenHash(byte[] hash);

        /** Marks the session used; false if someone else used it first (concurrent refresh). */
        boolean markUsed(UUID id, Instant now);

        void revokeFamily(UUID familyId, Instant now);
    }

    public interface PasswordHasher {
        String hash(String password);

        boolean matches(String password, String hash);
    }

    /** Signed, short-lived access tokens. */
    public interface AccessTokens {
        String issue(UUID userId, UUID deviceId, Instant now);

        long lifetimeSeconds();
    }

    /** Throttles repeated failures (login brute force, registration spam). */
    public interface AttemptLimiter {
        boolean allowed(String key, Instant now);

        void failed(String key, Instant now);

        void succeeded(String key);
    }
}
