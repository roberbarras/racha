package io.github.roberbarras.racha.config;

import io.github.roberbarras.racha.identity.adapter.mail.SmtpMailer;
import io.github.roberbarras.racha.identity.adapter.persistence.JdbcDevices;
import io.github.roberbarras.racha.identity.adapter.persistence.JdbcPasswordResets;
import io.github.roberbarras.racha.identity.adapter.persistence.JdbcSessions;
import io.github.roberbarras.racha.identity.adapter.persistence.JdbcUsers;
import io.github.roberbarras.racha.identity.adapter.security.BCryptPasswords;
import io.github.roberbarras.racha.identity.adapter.security.InMemoryAttemptLimiter;
import io.github.roberbarras.racha.identity.adapter.security.JwtAccessTokens;
import io.github.roberbarras.racha.identity.application.AuthService;
import io.github.roberbarras.racha.identity.application.PasswordService;
import io.github.roberbarras.racha.identity.domain.Email;
import io.github.roberbarras.racha.identity.domain.IdentityPorts;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.transaction.support.TransactionTemplate;

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
                "register", limits.registrationsPerClient(),
                "password", 10, // wrong current password, per account
                "reset-request", 5, "reset-request-client", 20, // recovery mails, per account and per address
                "reset", 10, "reset-client", 50), Duration.ofMinutes(15)); // recovery codes typed
    }

    @Bean
    IdentityPorts.PasswordResets passwordResets(JdbcClient jdbc, TransactionTemplate transactions) {
        return new JdbcPasswordResets(jdbc, transactions);
    }

    @Bean
    IdentityPorts.Mailer mailer(RachaProperties properties) {
        var mail = properties.mail();
        if (mail == null || !mail.configured()) return SmtpMailer.disabled();
        var sender = new JavaMailSenderImpl();
        sender.setHost(mail.host());
        sender.setPort(mail.port());
        if (mail.username() != null && !mail.username().isBlank()) {
            sender.setUsername(mail.username());
            sender.setPassword(mail.password());
        }
        var props = sender.getJavaMailProperties();
        props.put("mail.smtp.auth", String.valueOf(mail.username() != null && !mail.username().isBlank()));
        var security = mail.security() == null ? "starttls" : mail.security().toLowerCase(java.util.Locale.ROOT);
        props.put("mail.smtp.starttls.enable", String.valueOf(security.equals("starttls")));
        props.put("mail.smtp.starttls.required", String.valueOf(security.equals("starttls")));
        props.put("mail.smtp.ssl.enable", String.valueOf(security.equals("ssl")));
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "15000");
        props.put("mail.smtp.writetimeout", "15000");
        var from = mail.from() == null || mail.from().isBlank() ? mail.username() : mail.from();
        return new SmtpMailer(sender, from, mail.fromName());
    }

    @Bean
    PasswordService passwordService(
            IdentityPorts.Users users, IdentityPorts.Sessions sessions, IdentityPorts.PasswordResets resets, IdentityPorts.Mailer mailer,
            IdentityPorts.AttemptLimiter limiter, Clock clock, RachaProperties properties) {
        return new PasswordService(users, sessions, resets, new BCryptPasswords(), mailer, limiter, clock, properties.publicUrl());
    }

    /** `RACHA_ADMIN_EMAIL`'s account becomes an admin as soon as the server starts (if it exists already). */
    @Bean
    ApplicationRunner promoteConfiguredAdmin(AuthService auth) {
        return args -> auth.promoteConfiguredAdmin();
    }

    @Bean
    AuthService authService(
            IdentityPorts.Users users, IdentityPorts.Devices devices, IdentityPorts.Sessions sessions, IdentityPorts.AttemptLimiter limiter,
            JwtEncoder encoder, Clock clock, RachaProperties properties) {
        var auth = properties.auth();
        return new AuthService(users, devices, sessions, new BCryptPasswords(),
                new JwtAccessTokens(encoder, Duration.ofMinutes(auth.accessTokenMinutes())), limiter, clock,
                Duration.ofDays(auth.refreshTokenDays()), auth.registrationOpen(),
                auth.adminEmail() == null || auth.adminEmail().isBlank() ? null : Email.of(auth.adminEmail()));
    }
}
