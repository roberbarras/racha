package io.github.roberbarras.racha.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import io.github.roberbarras.racha.identity.adapter.security.JwtAccessTokens;
import io.github.roberbarras.racha.identity.application.AuthService;
import io.github.roberbarras.racha.shared.adapter.web.ClientAddressFilter;
import io.github.roberbarras.racha.shared.adapter.web.TrustedProxies;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.DelegatingRequestMatcherHeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Stateless API: public auth and docs endpoints, admin-only `/admin`, a valid access token for everything else. */
@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {
    private static final Logger log = LoggerFactory.getLogger(SecurityConfiguration.class);
    private static final int MIN_KEY_BYTES = 32;

    /**
     * Prometheus scrapes `/actuator/prometheus` with `Authorization: Bearer <RACHA_METRICS_TOKEN>` (it cannot sign in);
     * without that setting the endpoint is closed. Admins see the same figures in the admin screen.
     */
    @Bean
    @Order(1)
    SecurityFilterChain metrics(HttpSecurity http, RachaProperties properties) throws Exception {
        var token = properties.metricsToken();
        var expected = token == null || token.isBlank() ? null : ("Bearer " + token.strip()).getBytes(StandardCharsets.UTF_8);
        return http
                .securityMatcher("/actuator/prometheus")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().access((authentication, context) -> {
                    var header = context.getRequest().getHeader("Authorization");
                    var given = header == null ? new byte[0] : header.getBytes(StandardCharsets.UTF_8);
                    return new AuthorizationDecision(expected != null && MessageDigest.isEqual(expected, given));
                }))
                .build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain api(HttpSecurity http, AuthService accounts) throws Exception {
        return http
                .csrf(csrf -> csrf.disable()) // bearer tokens only, no cookies: CSRF does not apply
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/auth/**", "/actuator/health/**", "/v3/api-docs/**", "/docs/**", "/swagger-ui/**").permitAll()
                        .requestMatchers("/reset-password", "/reset-password.html", "/", "/web", "/web/**").permitAll()
                        // Checked against the database on every call: taking the role away works at once.
                        .requestMatchers("/admin/**").access((authentication, context) -> new AuthorizationDecision(
                                authentication.get() instanceof JwtAuthenticationToken jwt && accounts.isAdmin(UUID.fromString(jwt.getName()))))
                        .anyRequest().authenticated())
                .oauth2ResourceServer(rs -> rs.jwt(Customizer.withDefaults()))
                // The web panel runs only its own code and styles, cannot be framed, and talks only to this server.
                .headers(headers -> headers.addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(
                        PathPatternRequestMatcher.withDefaults().matcher("/web/**"),
                        new StaticHeadersWriter("Content-Security-Policy", WEB_CSP))))
                .build();
    }

    static final String WEB_CSP = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; "
            + "font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'";

    /** The password recovery page at `/reset-password` (the link in recovery e-mails) and the web panel at `/web/`. */
    @Bean
    WebMvcConfigurer pages() {
        return new WebMvcConfigurer() {
            @Override
            public void addViewControllers(ViewControllerRegistry registry) {
                registry.addViewController("/reset-password").setViewName("forward:/reset-password.html");
                // The web panel (H14) is the server's home page.
                registry.addViewController("/").setViewName("redirect:web/");
                registry.addViewController("/web").setViewName("redirect:web/");
                registry.addViewController("/web/").setViewName("forward:/web/index.html");
            }
        };
    }

    /** Real client addresses behind a trusted proxy, before anything else (security, throttling, logs) reads them. */
    @Bean
    FilterRegistrationBean<ClientAddressFilter> clientAddressFilter(RachaProperties properties) {
        var proxies = TrustedProxies.parse(properties.network() == null ? null : properties.network().trustedProxies());
        var registration = new FilterRegistrationBean<>(new ClientAddressFilter(proxies));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    @Bean
    SecretKey accessTokenKey(RachaProperties properties) {
        var configured = properties.auth().jwtSecret();
        if (configured == null || configured.isBlank()) {
            // Sessions (refresh tokens) are in the database and survive; only access tokens issued before a restart stop
            // working, and devices renew them on their next request.
            log.warn("RACHA_JWT_SECRET is not set: using a random key; after each restart devices renew their access token once");
            var random = new byte[MIN_KEY_BYTES];
            new SecureRandom().nextBytes(random);
            return new SecretKeySpec(random, "HmacSHA256");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(configured);
        } catch (IllegalArgumentException notBase64) {
            bytes = configured.getBytes(StandardCharsets.UTF_8);
        }
        if (bytes.length < MIN_KEY_BYTES) throw new IllegalStateException("RACHA_JWT_SECRET must be at least 32 bytes");
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey key) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(key));
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey key) {
        var decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(JwtAccessTokens.ISSUER)));
        return decoder;
    }
}
