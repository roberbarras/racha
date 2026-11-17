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

        void updatePassword(UUID id, String passwordHash, Instant now);

        boolean hasAdmin();

        /** Makes the account with this email an admin; false if there is none. */
        boolean promote(Email email, Instant now);
    }

    public interface Devices {
        void upsert(Device device);

        Optional<Device> byId(UUID id);

        List<Device> ofUser(UUID userId);

        void touch(UUID id, Instant now);

        /** Removes one of the user's devices and its sessions; false if it is not theirs. */
        boolean delete(UUID userId, UUID deviceId);
    }

    public interface Sessions {
        void insert(RefreshSession session);

        Optional<RefreshSession> byTokenHash(byte[] hash);

        /** Marks the session used; false if someone else used it first (concurrent refresh). */
        boolean markUsed(UUID id, Instant now);

        void revokeFamily(UUID familyId, Instant now);

        /** Ends every session of the user except those of [keepDevice] (null: all of them). */
        void revokeAllOfUser(UUID userId, UUID keepDevice, Instant now);

        /** Ends the sessions of one of the user's devices; false if none was active. */
        boolean revokeDevice(UUID userId, UUID deviceId, Instant now);

        /** Active (not expired, not revoked, not yet rotated) sessions per device of the user. */
        java.util.Map<UUID, Integer> activeByDevice(UUID userId, Instant now);
    }

    /** Password recovery codes (hashes only). */
    public interface PasswordResets {
        /** Stores [reset] and voids the user's earlier codes. */
        void replace(PasswordReset reset, String requestedFrom);

        /** The user's newest code, if any. */
        Optional<PasswordReset> latestOf(UUID userId);

        /** Marks it used; false if it was used meanwhile. */
        boolean markUsed(UUID id, Instant now);
    }

    /** Outgoing e-mail (SMTP); [configured] is false when the server has no mail settings. */
    public interface Mailer {
        boolean configured();

        /** Sends a plain-text message; throws if the mail server refuses it. */
        void send(String to, String subject, String body);
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
