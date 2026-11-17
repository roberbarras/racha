package io.github.roberbarras.racha.admin.application;

/** Admin actions that are refused; the web adapter maps them to problems. */
public sealed class AdminErrors extends RuntimeException {
    private AdminErrors(String message) {
        super(message);
    }

    public static final class UnknownUser extends AdminErrors {
        public UnknownUser() { super("no such user"); }
    }

    /** The server must keep one active admin, and an admin cannot lock themselves out. */
    public static final class LastAdmin extends AdminErrors {
        public LastAdmin() { super("the server needs at least one active admin"); }
    }

    public static final class MailFailed extends AdminErrors {
        public MailFailed(String reason) { super(reason); }
    }

    public static final class InvalidInput extends AdminErrors {
        public InvalidInput(String message) { super(message); }
    }
}
