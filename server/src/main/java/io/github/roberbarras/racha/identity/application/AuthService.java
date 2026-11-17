package io.github.roberbarras.racha.identity.application;

import io.github.roberbarras.racha.identity.domain.Device;
import io.github.roberbarras.racha.identity.domain.Email;
import io.github.roberbarras.racha.identity.domain.IdentityPorts;
import io.github.roberbarras.racha.identity.domain.LearningMode;
import io.github.roberbarras.racha.identity.domain.PasswordPolicy;
import io.github.roberbarras.racha.identity.domain.RefreshSession;
import io.github.roberbarras.racha.identity.domain.Role;
import io.github.roberbarras.racha.identity.domain.User;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Accounts and sessions (ADR-0006, §7.2): email + password, short access tokens, rotating refresh tokens bound to a
 * device. Failures never reveal whether an email exists.
 */
public final class AuthService {
    private final IdentityPorts.Users users;
    private final IdentityPorts.Devices devices;
    private final IdentityPorts.Sessions sessions;
    private final IdentityPorts.PasswordHasher passwords;
    private final IdentityPorts.AccessTokens accessTokens;
    private final IdentityPorts.AttemptLimiter limiter;
    private final Clock clock;
    private final Duration refreshLifetime;
    private final boolean registrationOpen;
    /** `RACHA_ADMIN_EMAIL`: that account administers the server; null: the first account to register does. */
    private final Email adminEmail;
    /** Compared against when the email is unknown, so both cases cost the same time. */
    private final String dummyHash;

    public AuthService(
            IdentityPorts.Users users, IdentityPorts.Devices devices, IdentityPorts.Sessions sessions,
            IdentityPorts.PasswordHasher passwords, IdentityPorts.AccessTokens accessTokens, IdentityPorts.AttemptLimiter limiter,
            Clock clock, Duration refreshLifetime, boolean registrationOpen, Email adminEmail) {
        this.users = users;
        this.devices = devices;
        this.sessions = sessions;
        this.passwords = passwords;
        this.accessTokens = accessTokens;
        this.limiter = limiter;
        this.clock = clock;
        this.refreshLifetime = refreshLifetime;
        this.registrationOpen = registrationOpen;
        this.adminEmail = adminEmail;
        this.dummyHash = passwords.hash(RefreshTokens.generate());
    }

    public AuthTokens register(String rawEmail, String password, String displayName, DeviceInfo device, String clientKey) {
        var now = clock.instant();
        if (!registrationOpen) throw new AuthErrors.RegistrationClosed();
        var limitKey = "register:" + clientKey;
        if (!limiter.allowed(limitKey, now)) throw new AuthErrors.TooManyAttempts();
        var email = email(rawEmail);
        if (!PasswordPolicy.accepts(password)) {
            throw new AuthErrors.InvalidInput("password must be " + PasswordPolicy.MIN_LENGTH + "–" + PasswordPolicy.MAX_LENGTH + " characters");
        }
        if (displayName != null && displayName.length() > 100) throw new AuthErrors.InvalidInput("display name too long");
        limiter.failed(limitKey, now); // counts every registration from this client, successful or not
        var admin = adminEmail != null ? adminEmail.equals(email) : !users.hasAdmin();
        var user = new User(UUID.randomUUID(), email, passwords.hash(password), blankToNull(displayName), LearningMode.STUDY, now, false,
                admin ? Role.ADMIN : Role.USER);
        if (!users.insert(user, now)) throw new AuthErrors.EmailTaken();
        return startSession(user, device, now, null);
    }

    public AuthTokens login(String rawEmail, String password, DeviceInfo device, String clientKey) {
        var now = clock.instant();
        Email email;
        try {
            email = Email.of(rawEmail);
        } catch (IllegalArgumentException e) {
            throw new AuthErrors.InvalidCredentials();
        }
        // Failures lock this account from this address only, and this address for every account: someone guessing a
        // password can neither lock its owner out from elsewhere nor anybody else.
        var accountKey = "login:" + email.value() + "|" + clientKey;
        var clientLimit = "login-client:" + clientKey;
        if (!limiter.allowed(accountKey, now) || !limiter.allowed(clientLimit, now)) throw new AuthErrors.TooManyAttempts();
        var user = users.byEmail(email).filter(u -> !u.disabled());
        var given = password == null ? "" : password;
        boolean ok;
        if (user.isPresent()) {
            ok = passwords.matches(given, user.get().passwordHash());
        } else {
            passwords.matches(given, dummyHash); // same cost as a real check: timing does not reveal accounts
            ok = false;
        }
        if (!ok) {
            limiter.failed(accountKey, now);
            limiter.failed(clientLimit, now);
            throw new AuthErrors.InvalidCredentials();
        }
        limiter.succeeded(accountKey);
        return startSession(user.get(), device, now, null);
    }

    /** Rotates [refreshToken]. A token used twice revokes its whole family (stolen token). */
    public AuthTokens refresh(String refreshToken) {
        var now = clock.instant();
        if (refreshToken == null || refreshToken.isBlank()) throw new AuthErrors.InvalidRefreshToken();
        var session = sessions.byTokenHash(RefreshTokens.hash(refreshToken)).orElseThrow(AuthErrors.InvalidRefreshToken::new);
        switch (session.stateAt(now)) {
            case ACTIVE -> { }
            case USED -> {
                sessions.revokeFamily(session.familyId(), now);
                throw new AuthErrors.InvalidRefreshToken();
            }
            case EXPIRED, REVOKED -> throw new AuthErrors.InvalidRefreshToken();
        }
        if (!sessions.markUsed(session.id(), now)) {
            sessions.revokeFamily(session.familyId(), now);
            throw new AuthErrors.InvalidRefreshToken();
        }
        var user = users.byId(session.userId()).filter(u -> !u.disabled()).orElseThrow(AuthErrors.InvalidRefreshToken::new);
        if (session.deviceId() != null) devices.touch(session.deviceId(), now);
        return issue(user.id(), session.deviceId(), session.familyId(), now);
    }

    /** At start-up: `RACHA_ADMIN_EMAIL`'s account, if it exists already, becomes an admin. */
    public void promoteConfiguredAdmin() {
        if (adminEmail != null) users.promote(adminEmail, clock.instant());
    }

    public boolean isAdmin(UUID userId) {
        return users.byId(userId).filter(u -> !u.disabled()).map(User::isAdmin).orElse(false);
    }

    /** Ends this device's session (the token and every token rotated from the same login). */
    public void logout(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) return;
        sessions.byTokenHash(RefreshTokens.hash(refreshToken)).ifPresent(s -> sessions.revokeFamily(s.familyId(), clock.instant()));
    }

    private AuthTokens startSession(User user, DeviceInfo info, Instant now, UUID familyId) {
        var device = registerDevice(user.id(), info, now);
        return issue(user.id(), device.id(), familyId == null ? UUID.randomUUID() : familyId, now);
    }

    /** Creates the device, or updates it when [info] names one of this user's devices. */
    public Device registerDevice(UUID userId, DeviceInfo info, Instant now) {
        if (info == null) throw new AuthErrors.InvalidInput("device is required");
        var name = info.checkedName();
        var platform = info.parsedPlatform();
        var existing = info.id() == null ? null : devices.byId(info.id()).filter(d -> d.userId().equals(userId)).orElse(null);
        var device = existing == null
                ? new Device(UUID.randomUUID(), userId, name, platform, info.appVersion(), now, now)
                : new Device(existing.id(), userId, name, platform, info.appVersion(), existing.createdAt(), now);
        devices.upsert(device);
        return device;
    }

    private AuthTokens issue(UUID userId, UUID deviceId, UUID familyId, Instant now) {
        var token = RefreshTokens.generate();
        sessions.insert(new RefreshSession(UUID.randomUUID(), userId, deviceId, familyId, RefreshTokens.hash(token),
                now, now.plus(refreshLifetime), null, null));
        return new AuthTokens(userId, deviceId, accessTokens.issue(userId, deviceId, now), accessTokens.lifetimeSeconds(), token);
    }

    private static Email email(String raw) {
        try {
            return Email.of(raw);
        } catch (IllegalArgumentException e) {
            throw new AuthErrors.InvalidInput("invalid email");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
