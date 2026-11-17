package io.github.roberbarras.racha.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** `racha.*` settings (application.yaml, overridable by RACHA_* environment variables). */
@ConfigurationProperties("racha")
public record RachaProperties(Auth auth, Limits limits, Network network) {
    public record Auth(String jwtSecret, int accessTokenMinutes, int refreshTokenDays, String registration) {
        public boolean registrationOpen() {
            return !"closed".equalsIgnoreCase(registration);
        }
    }

    /**
     * Per 15 minutes: [loginFailuresPerAccountAndClient] locks one account from one address only (the owner can still
     * sign in from elsewhere); [loginFailuresPerClient] and [registrationsPerClient] lock one address. Nothing locks
     * everybody.
     */
    public record Limits(int maxBatch, int maxChangeBytes, int loginFailuresPerAccountAndClient, int loginFailuresPerClient, int registrationsPerClient) {}

    /** [trustedProxies]: who may tell the client's address (`local`, `cloudflare`, `none` or CIDRs, comma-separated). */
    public record Network(String trustedProxies) {}
}
