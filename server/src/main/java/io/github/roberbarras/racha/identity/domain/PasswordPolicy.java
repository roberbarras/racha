package io.github.roberbarras.racha.identity.domain;

/** NIST SP 800-63B style: length is what matters; no composition rules, long passphrases welcome. */
public final class PasswordPolicy {
    public static final int MIN_LENGTH = 10;
    public static final int MAX_LENGTH = 128;

    private PasswordPolicy() {}

    public static boolean accepts(String password) {
        if (password == null) return false;
        int length = password.codePointCount(0, password.length());
        return length >= MIN_LENGTH && length <= MAX_LENGTH && !password.isBlank();
    }
}
