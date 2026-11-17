package io.github.roberbarras.racha;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** §7.2 / ADR-0006 against a real PostgreSQL. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class AuthApiTest {
    @Value("${local.server.port}")
    int port;
    Api api;

    @BeforeEach
    void client() {
        api = new Api(port);
    }

    @Test
    void registerThenUseTheAccessToken() {
        var r = api.post("/auth/register", Map.of("email", "  Ana.Lopez@Example.COM ", "password", "correct horse battery",
                "displayName", "Ana", "device", Api.device("Portátil")), null);
        assertThat(r.status()).isEqualTo(201);
        assertThat(r.body().get("tokenType").asString()).isEqualTo("Bearer");
        assertThat(r.body().get("expiresIn").asLong()).isEqualTo(15 * 60);
        var session = Api.session(r.body());

        var devices = api.get("/devices", session.access());
        assertThat(devices.status()).isEqualTo(200);
        assertThat(devices.body().get(0).get("name").asString()).isEqualTo("Portátil");

        // The email is normalised: the same address in other case is the same account.
        var again = api.post("/auth/register", Map.of("email", "ana.lopez@example.com", "password", "another long password", "device", Api.device("x")), null);
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.body().get("code").asString()).isEqualTo("email_taken");
        assertThat(api.login("ANA.LOPEZ@example.com", "correct horse battery", "Móvil").userId()).isEqualTo(session.userId());
    }

    @Test
    void rejectsBadInput() {
        assertThat(api.post("/auth/register", Map.of("email", "not-an-email", "password", "correct horse battery", "device", Api.device("x")), null).status()).isEqualTo(400);
        var shortPassword = api.post("/auth/register", Map.of("email", UUID.randomUUID() + "@example.com", "password", "short", "device", Api.device("x")), null);
        assertThat(shortPassword.status()).isEqualTo(400);
        assertThat(shortPassword.body().get("code").asString()).isEqualTo("invalid_input");
        var badPlatform = api.post("/auth/register", Map.of("email", UUID.randomUUID() + "@example.com", "password", "correct horse battery",
                "device", Map.of("name", "x", "platform", "windows")), null);
        assertThat(badPlatform.status()).isEqualTo(400);
        assertThat(api.post("/auth/login", "{not json", null).status()).isEqualTo(400);
    }

    @Test
    void wrongPasswordAndUnknownEmailLookTheSame() {
        var email = "u-" + UUID.randomUUID() + "@example.com";
        api.post("/auth/register", Map.of("email", email, "password", "correct horse battery", "device", Api.device("x")), null);
        var wrong = api.post("/auth/login", Map.of("email", email, "password", "wrong password!!", "device", Api.device("x")), null);
        var unknown = api.post("/auth/login", Map.of("email", "nobody-" + UUID.randomUUID() + "@example.com", "password", "whatever123", "device", Api.device("x")), null);
        assertThat(wrong.status()).isEqualTo(401);
        assertThat(unknown.status()).isEqualTo(401);
        assertThat(wrong.body().get("detail")).isEqualTo(unknown.body().get("detail"));
    }

    @Test
    void refreshRotatesAndAReusedTokenRevokesTheFamily() {
        var first = api.register("Móvil");
        var second = api.post("/auth/refresh", Map.of("refreshToken", first.refresh()), null);
        assertThat(second.status()).isEqualTo(200);
        var rotated = Api.session(second.body());
        assertThat(rotated.refresh()).isNotEqualTo(first.refresh());
        assertThat(rotated.deviceId()).isEqualTo(first.deviceId());

        // The old token again: someone copied it. Both it and the one rotated from it stop working.
        assertThat(api.post("/auth/refresh", Map.of("refreshToken", first.refresh()), null).status()).isEqualTo(401);
        assertThat(api.post("/auth/refresh", Map.of("refreshToken", rotated.refresh()), null).status()).isEqualTo(401);
    }

    @Test
    void logoutEndsTheSession() {
        var s = api.register("Móvil");
        assertThat(api.post("/auth/logout", Map.of("refreshToken", s.refresh()), null).status()).isEqualTo(204);
        assertThat(api.post("/auth/refresh", Map.of("refreshToken", s.refresh()), null).status()).isEqualTo(401);
    }

    @Test
    void protectedEndpointsNeedAValidToken() {
        assertThat(api.get("/devices", null).status()).isEqualTo(401);
        var s = api.register("x");
        var tampered = s.access().substring(0, s.access().length() - 4) + "AAAA";
        assertThat(api.get("/devices", tampered).status()).isEqualTo(401);
        assertThat(api.get("/sync/pull", "not.a.jwt").status()).isEqualTo(401);
    }

    @Test
    void failedLoginsLockThatAccountFromThatAddressOnly() {
        var email = "t-" + UUID.randomUUID() + "@example.com";
        var other = "o-" + UUID.randomUUID() + "@example.com";
        for (var e : java.util.List.of(email, other)) {
            api.post("/auth/register", Map.of("email", e, "password", "correct horse battery", "device", Api.device("x")), null);
        }
        var attacker = "203.0.113." + (1 + new java.util.Random().nextInt(250));
        for (int i = 0; i < 10; i++) {
            assertThat(login(email, "wrong password " + i, attacker).status()).isEqualTo(401);
        }
        var blocked = login(email, "correct horse battery", attacker);
        assertThat(blocked.status()).isEqualTo(429);
        assertThat(blocked.body().get("code").asString()).isEqualTo("too_many_attempts");

        // The owner, elsewhere, is not locked out; nor is anybody else, even from the attacker's address.
        assertThat(login(email, "correct horse battery", "198.51.100.40").status()).isEqualTo(200);
        assertThat(login(other, "correct horse battery", attacker).status()).isEqualTo(200);
    }

    @Test
    void anAddressFailingTooOftenIsLockedForEveryAccountButNobodyElseIs() {
        var email = "c-" + UUID.randomUUID() + "@example.com";
        api.post("/auth/register", Map.of("email", email, "password", "correct horse battery", "device", Api.device("x")), null);
        var attacker = "2001:db8::" + Integer.toHexString(1 + new java.util.Random().nextInt(60000));
        for (int i = 0; i < 50; i++) {
            assertThat(login("nobody-" + i + "@example.com", "wrong password", attacker).status()).isEqualTo(401);
        }
        assertThat(login(email, "correct horse battery", attacker).status()).isEqualTo(429);
        assertThat(login(email, "correct horse battery", "198.51.100.41").status()).isEqualTo(200);
    }

    private Api.Response login(String email, String password, String from) {
        return api.call("POST", "/auth/login", Map.of("email", email, "password", password, "device", Api.device("x")), null, from);
    }
}
