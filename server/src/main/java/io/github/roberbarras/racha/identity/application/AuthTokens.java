package io.github.roberbarras.racha.identity.application;

import java.util.UUID;

/** What a successful login, registration or refresh returns. */
public record AuthTokens(UUID userId, UUID deviceId, String accessToken, long expiresInSeconds, String refreshToken) {}
