package io.github.roberbarras.racha.identity.adapter.security;

import io.github.roberbarras.racha.identity.domain.IdentityPorts;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

/** HS256 access tokens: `sub` = user, `did` = device. Verified by the resource-server filter with the same key. */
public final class JwtAccessTokens implements IdentityPorts.AccessTokens {
    public static final String ISSUER = "racha";
    public static final String DEVICE_CLAIM = "did";

    private final JwtEncoder encoder;
    private final Duration lifetime;

    public JwtAccessTokens(JwtEncoder encoder, Duration lifetime) {
        this.encoder = encoder;
        this.lifetime = lifetime;
    }

    @Override
    public String issue(UUID userId, UUID deviceId, Instant now) {
        var claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(now.plus(lifetime))
                .claim(DEVICE_CLAIM, deviceId == null ? "" : deviceId.toString())
                .build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    @Override
    public long lifetimeSeconds() {
        return lifetime.toSeconds();
    }
}
