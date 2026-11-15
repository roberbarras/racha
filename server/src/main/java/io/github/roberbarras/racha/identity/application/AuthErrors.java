package io.github.roberbarras.racha.identity.application;

/** Failures of the auth use cases; the web adapter maps each to an HTTP problem. */
public sealed class AuthErrors extends RuntimeException {
    private AuthErrors(String message) {
        super(message);
    }

    /** Wrong email or password (never says which). */
    public static final class InvalidCredentials extends AuthErrors {
        public InvalidCredentials() { super("invalid email or password"); }
    }

    public static final class EmailTaken extends AuthErrors {
        public EmailTaken() { super("an account with this email already exists"); }
    }

    public static final class RegistrationClosed extends AuthErrors {
        public RegistrationClosed() { super("this server does not accept new accounts"); }
    }

    public static final class TooManyAttempts extends AuthErrors {
        public TooManyAttempts() { super("too many attempts, try again later"); }
    }

    /** Unknown, expired, revoked or reused refresh token: the client must log in again. */
    public static final class InvalidRefreshToken extends AuthErrors {
        public InvalidRefreshToken() { super("session expired, log in again"); }
    }

    public static final class InvalidInput extends AuthErrors {
        public InvalidInput(String message) { super(message); }
    }
}
