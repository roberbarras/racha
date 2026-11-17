package io.github.roberbarras.racha.config;

import io.github.roberbarras.racha.identity.adapter.persistence.JdbcDevices;
import io.github.roberbarras.racha.identity.adapter.persistence.JdbcSessions;
import io.github.roberbarras.racha.identity.adapter.persistence.JdbcUsers;
import io.github.roberbarras.racha.identity.adapter.security.BCryptPasswords;
import io.github.roberbarras.racha.identity.adapter.security.InMemoryAttemptLimiter;
import io.github.roberbarras.racha.identity.adapter.security.JwtAccessTokens;
import io.github.roberbarras.racha.identity.application.AuthService;
import io.github.roberbarras.racha.identity.domain.IdentityPorts;
import java.time.Clock;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwtEncoder;

/** Composition of the identity context (adapters into ports, ports into the use cases). */
@Configuration(proxyBeanMethods = false)
public class IdentityConfiguration {
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    IdentityPorts.Users users(JdbcClient jdbc) {
        return new JdbcUsers(jdbc);
    }

    @Bean
    IdentityPorts.Devices devices(JdbcClient jdbc) {
        return new JdbcDevices(jdbc);
    }

    @Bean
    IdentityPorts.Sessions sessions(JdbcClient jdbc) {
        return new JdbcSessions(jdbc);
    }

    @Bean
    IdentityPorts.AttemptLimiter attemptLimiter(RachaProperties properties) {
        var limits = properties.limits();
        return new InMemoryAttemptLimiter(java.util.Map.of(
                "login", limits.loginFailuresPerAccountAndClient(),
                "login-client", limits.loginFailuresPerClient(),
                "register", limits.registrationsPerClient()), Duration.ofMinutes(15));
    }

    @Bean
    AuthService authService(
            IdentityPorts.Users users, IdentityPorts.Devices devices, IdentityPorts.Sessions sessions, IdentityPorts.AttemptLimiter limiter,
            JwtEncoder encoder, Clock clock, RachaProperties properties) {
        var auth = properties.auth();
        return new AuthService(users, devices, sessions, new BCryptPasswords(),
                new JwtAccessTokens(encoder, Duration.ofMinutes(auth.accessTokenMinutes())), limiter, clock,
                Duration.ofDays(auth.refreshTokenDays()), auth.registrationOpen());
    }
}
