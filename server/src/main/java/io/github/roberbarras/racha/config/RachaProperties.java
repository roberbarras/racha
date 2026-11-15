package io.github.roberbarras.racha.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** `racha.*` settings (application.yaml, overridable by RACHA_* environment variables). */
@ConfigurationProperties("racha")
public record RachaProperties(Auth auth, Limits limits) {
    public record Auth(String jwtSecret, int accessTokenMinutes, int refreshTokenDays, String registration) {
        public boolean registrationOpen() {
            return !"closed".equalsIgnoreCase(registration);
        }
    }

    /**
     * [loginFailuresPerEmail] protects one account; [loginFailuresPerClient] and [registrationsPerClient] one address
     * (each per 15 minutes).
     */
    public record Limits(int maxBatch, int maxChangeBytes, int loginFailuresPerEmail, int loginFailuresPerClient, int registrationsPerClient) {}
}
