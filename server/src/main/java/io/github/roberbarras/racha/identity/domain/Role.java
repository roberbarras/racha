package io.github.roberbarras.racha.identity.domain;

/** What an account may do: every user owns their data; an admin also sees the server's health, users and logs. */
public enum Role {
    USER, ADMIN;

    public String wire() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    public static Role fromWire(String value) {
        return valueOf(value.toUpperCase(java.util.Locale.ROOT));
    }
}
