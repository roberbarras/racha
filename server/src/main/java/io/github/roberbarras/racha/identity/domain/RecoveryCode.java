package io.github.roberbarras.racha.identity.domain;

import java.security.SecureRandom;
import java.util.Locale;

/**
 * The code e-mailed to recover a password: 8 characters (40 bits) from an alphabet without look-alikes, shown as
 * `XXXX-XXXX`. Short enough to type on a phone; guessing is stopped by expiry, single use and attempt limits.
 */
public final class RecoveryCode {
    private static final String ALPHABET = "ABCDEFGHJKMNPQRSTVWXYZ23456789"; // no I, L, O, U, 0, 1
    private static final SecureRandom RANDOM = new SecureRandom();
    public static final int LENGTH = 8;

    private RecoveryCode() {}

    public static String generate() {
        var code = new StringBuilder(LENGTH + 1);
        for (int i = 0; i < LENGTH; i++) {
            if (i == LENGTH / 2) code.append('-');
            code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }

    /** What the user typed, as it is hashed: upper case, without the dash or spaces. */
    public static String normalize(String typed) {
        if (typed == null) return "";
        return typed.toUpperCase(Locale.ROOT).replaceAll("[\\s-]", "");
    }
}
