package io.github.roberbarras.racha.identity.domain;

import java.util.Locale;
import java.util.regex.Pattern;

/** A user's login: trimmed, lower-case, one "@" with something on both sides and a dot in the domain. */
public record Email(String value) {
    private static final Pattern SHAPE = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final int MAX_LENGTH = 254;

    public Email {
        if (value == null || value.length() > MAX_LENGTH || !SHAPE.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid email");
        }
    }

    public static Email of(String raw) {
        return new Email(raw == null ? null : raw.strip().toLowerCase(Locale.ROOT));
    }
}
