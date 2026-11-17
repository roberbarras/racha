package io.github.roberbarras.racha.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import io.github.roberbarras.racha.identity.adapter.security.JwtAccessTokens;
import io.github.roberbarras.racha.shared.adapter.web.ClientAddressFilter;
import io.github.roberbarras.racha.shared.adapter.web.TrustedProxies;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
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
import org.springframework.security.web.SecurityFilterChain;

/** Stateless API: public auth and docs endpoints, a valid access token for everything else. */
@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {
    private static final Logger log = LoggerFactory.getLogger(SecurityConfiguration.class);
    private static final int MIN_KEY_BYTES = 32;

    @Bean
    SecurityFilterChain api(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable()) // bearer tokens only, no cookies: CSRF does not apply
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/auth/**", "/actuator/health/**", "/v3/api-docs/**", "/docs/**", "/swagger-ui/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(rs -> rs.jwt(Customizer.withDefaults()))
                .build();
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
