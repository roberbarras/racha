package io.github.roberbarras.racha.identity.adapter.security;

import io.github.roberbarras.racha.identity.domain.IdentityPorts;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** BCrypt (ADR-0006), cost 12: about a quarter of a second per check on a laptop, slow enough for brute force. */
public final class BCryptPasswords implements IdentityPorts.PasswordHasher {
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);

    @Override
    public String hash(String password) {
        return encoder.encode(password);
    }

    @Override
    public boolean matches(String password, String hash) {
        return encoder.matches(password, hash);
    }
}
