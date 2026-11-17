package io.github.roberbarras.racha.identity.application;

import io.github.roberbarras.racha.identity.domain.Email;
import io.github.roberbarras.racha.identity.domain.IdentityPorts;
import io.github.roberbarras.racha.identity.domain.PasswordPolicy;
import io.github.roberbarras.racha.identity.domain.PasswordReset;
import io.github.roberbarras.racha.identity.domain.RecoveryCode;
import io.github.roberbarras.racha.identity.domain.User;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Changing a password (signed in) and recovering it by e-mail (signed out). Recovery never reveals whether an account
 * exists: an unknown address gets the same answer and no mail. Every attempt is throttled per account and address.
 */
public final class PasswordService {
    private static final System.Logger LOG = System.getLogger(PasswordService.class.getName());
    static final Duration CODE_VALIDITY = Duration.ofMinutes(30);

    private final IdentityPorts.Users users;
    private final IdentityPorts.Sessions sessions;
    private final IdentityPorts.PasswordResets resets;
    private final IdentityPorts.PasswordHasher passwords;
    private final IdentityPorts.Mailer mailer;
    private final IdentityPorts.AttemptLimiter limiter;
    private final Clock clock;
    /** Public address of the server (`RACHA_PUBLIC_URL`) for the link in the mail; null: the code only. */
    private final String publicUrl;

    public PasswordService(
            IdentityPorts.Users users, IdentityPorts.Sessions sessions, IdentityPorts.PasswordResets resets,
            IdentityPorts.PasswordHasher passwords, IdentityPorts.Mailer mailer, IdentityPorts.AttemptLimiter limiter,
            Clock clock, String publicUrl) {
        this.users = users;
        this.sessions = sessions;
        this.resets = resets;
        this.passwords = passwords;
        this.mailer = mailer;
        this.limiter = limiter;
        this.clock = clock;
        this.publicUrl = publicUrl == null || publicUrl.isBlank() ? null : publicUrl.strip().replaceAll("/+$", "");
    }

    /** Signed in: the current password proves it is the owner. Other devices are signed out; this one stays. */
    public void change(UUID userId, UUID deviceId, String current, String replacement) {
        var now = clock.instant();
        var key = "password:" + userId;
        if (!limiter.allowed(key, now)) throw new AuthErrors.TooManyAttempts();
        var user = users.byId(userId).filter(u -> !u.disabled()).orElseThrow(AuthErrors.InvalidRefreshToken::new);
        if (!passwords.matches(current == null ? "" : current, user.passwordHash())) {
            limiter.failed(key, now);
            throw new AuthErrors.WrongPassword();
        }
        checkPolicy(replacement);
        limiter.succeeded(key);
        users.updatePassword(userId, passwords.hash(replacement), now);
        sessions.revokeAllOfUser(userId, deviceId, now);
    }

    /**
     * Sends a recovery code to [rawEmail] if it is an active account. Same outcome either way, except when the server
     * cannot send mail at all (that says nothing about accounts, and the user needs to know).
     */
    public void requestCode(String rawEmail, String language, String clientKey) {
        var now = clock.instant();
        if (!mailer.configured()) throw new AuthErrors.MailNotConfigured();
        var email = email(rawEmail);
        if (email.isEmpty()) return;
        var emailKey = "reset-request:" + email.get().value();
        var clientLimit = "reset-request-client:" + clientKey;
        if (!limiter.allowed(emailKey, now) || !limiter.allowed(clientLimit, now)) throw new AuthErrors.TooManyAttempts();
        limiter.failed(emailKey, now); // every request counts: a flood of mails is the abuse to stop
        limiter.failed(clientLimit, now);
        var user = users.byEmail(email.get()).filter(u -> !u.disabled());
        if (user.isEmpty()) return;
        var code = RecoveryCode.generate();
        resets.replace(new PasswordReset(UUID.randomUUID(), user.get().id(), hash(user.get(), code), now, now.plus(CODE_VALIDITY), null), clientKey);
        var link = publicUrl == null ? null
                : publicUrl + "/reset-password#email=" + java.net.URLEncoder.encode(email.get().value(), java.nio.charset.StandardCharsets.UTF_8) + "&code=" + code;
        var mail = RecoveryMail.of(language, code, link, CODE_VALIDITY);
        // In the background: answering only after the SMTP round trip would tell, by its delay, that the account exists.
        var to = email.get().value();
        Thread.ofVirtual().name("recovery-mail").start(() -> {
            try {
                mailer.send(to, mail.subject(), mail.body());
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "could not send the recovery mail (check RACHA_MAIL_* settings)", e);
            }
        });
    }

    /** Signed out: the e-mailed code proves it is the owner. Every session ends: whoever had the account is out. */
    public void reset(String rawEmail, String code, String replacement, String clientKey) {
        var now = clock.instant();
        var email = email(rawEmail).orElseThrow(AuthErrors.InvalidRecoveryCode::new);
        var emailKey = "reset:" + email.value();
        var clientLimit = "reset-client:" + clientKey;
        if (!limiter.allowed(emailKey, now) || !limiter.allowed(clientLimit, now)) throw new AuthErrors.TooManyAttempts();
        checkPolicy(replacement);
        var user = users.byEmail(email).filter(u -> !u.disabled());
        var reset = user.flatMap(u -> resets.latestOf(u.id())).filter(r -> r.usableAt(now));
        var matches = reset.isPresent() && java.security.MessageDigest.isEqual(reset.get().codeHash(), hash(user.get(), RecoveryCode.normalize(code)));
        if (!matches) {
            limiter.failed(emailKey, now);
            limiter.failed(clientLimit, now);
            throw new AuthErrors.InvalidRecoveryCode();
        }
        if (!resets.markUsed(reset.get().id(), now)) throw new AuthErrors.InvalidRecoveryCode();
        limiter.succeeded(emailKey);
        users.updatePassword(user.get().id(), passwords.hash(replacement), now);
        sessions.revokeAllOfUser(user.get().id(), null, now);
    }

    public boolean mailConfigured() {
        return mailer.configured();
    }

    private static byte[] hash(User user, String code) {
        return RefreshTokens.hash(user.id() + ":" + RecoveryCode.normalize(code));
    }

    private static void checkPolicy(String password) {
        if (!PasswordPolicy.accepts(password)) {
            throw new AuthErrors.InvalidInput("password must be " + PasswordPolicy.MIN_LENGTH + "–" + PasswordPolicy.MAX_LENGTH + " characters");
        }
    }

    private static Optional<Email> email(String raw) {
        try {
            return Optional.of(Email.of(raw));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
