package io.github.roberbarras.racha.shared.adapter.web;

import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;

/** Who is calling, from the verified access token: `sub` = user, `did` = device (empty for none). */
public record Caller(UUID userId, UUID deviceId) {
    public static Caller of(Jwt jwt) {
        var device = jwt.getClaimAsString("did");
        return new Caller(UUID.fromString(jwt.getSubject()), device == null || device.isEmpty() ? null : UUID.fromString(device));
    }
}
