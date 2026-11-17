package io.github.roberbarras.racha.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** `racha.*` settings (application.yaml, overridable by RACHA_* environment variables). */
@ConfigurationProperties("racha")
public record RachaProperties(Auth auth, Limits limits, Network network, Mail mail, String publicUrl, String backupDir, String metricsToken, String logFile) {
    /** [adminEmail]: the account that administers the server; empty: the first one to register. */
    public record Auth(String jwtSecret, int accessTokenMinutes, int refreshTokenDays, String registration, String adminEmail) {
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

    /** SMTP for recovery codes; no [host]: the server sends no mail. [security]: `starttls`, `ssl` or `none`. */
    public record Mail(String host, int port, String username, String password, String security, String from, String fromName) {
        public boolean configured() {
            return host != null && !host.isBlank();
        }
    }
}
