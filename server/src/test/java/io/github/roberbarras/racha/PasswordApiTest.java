package io.github.roberbarras.racha;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** Changing a password and recovering it by e-mail (H13), against a real PostgreSQL and a capturing mail server. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "racha.public-url=https://racha.example.com/")
@Import({TestcontainersConfiguration.class, CapturingMailer.class})
class PasswordApiTest {
    private static final Pattern CODE = Pattern.compile("([A-Z2-9]{4}-[A-Z2-9]{4})");
    private static final String PASSWORD = "correct horse battery";

    @Value("${local.server.port}")
    int port;
    Api api;

    @BeforeEach
    void client() {
        api = new Api(port);
    }

    @Test
    void changingThePasswordNeedsTheCurrentOneAndSignsOtherDevicesOut() {
        var email = "p-" + UUID.randomUUID() + "@example.com";
        var laptop = Api.session(api.post("/auth/register", Map.of("email", email, "password", PASSWORD, "device", Api.device("Portátil")), null).body());
        var phone = api.login(email, PASSWORD, "Móvil");

        var wrong = api.post("/account/password", Map.of("currentPassword", "not my password", "newPassword", "a brand new password"), laptop.access());
        assertThat(wrong.status()).isEqualTo(403); // not 401: the token is fine, the client must not sign out
        assertThat(wrong.body().get("code").asString()).isEqualTo("wrong_password");
        assertThat(api.post("/account/password", Map.of("currentPassword", PASSWORD, "newPassword", "short"), laptop.access()).status()).isEqualTo(400);

        assertThat(api.post("/account/password", Map.of("currentPassword", PASSWORD, "newPassword", "a brand new password"), laptop.access()).status())
                .isEqualTo(204);

        assertThat(api.post("/auth/login", Map.of("email", email, "password", PASSWORD, "device", Api.device("x")), null).status()).isEqualTo(401);
        assertThat(api.post("/auth/login", Map.of("email", email, "password", "a brand new password", "device", Api.device("x")), null).status()).isEqualTo(200);
        assertThat(api.post("/auth/refresh", Map.of("refreshToken", phone.refresh()), null).status()).as("the phone is signed out").isEqualTo(401);
        assertThat(api.post("/auth/refresh", Map.of("refreshToken", laptop.refresh()), null).status()).as("this device stays").isEqualTo(200);
    }

    @Test
    void aForgottenPasswordIsResetWithTheEmailedCodeOnce() {
        var email = "r-" + UUID.randomUUID() + "@example.com";
        var old = Api.session(api.post("/auth/register", Map.of("email", email, "password", PASSWORD, "device", Api.device("x")), null).body());

        var request = api.call("POST", "/auth/password/forgot", Map.of("email", email.toUpperCase()), null);
        assertThat(request.status()).isEqualTo(202);
        var mail = CapturingMailer.awaitTo(email);
        var matcher = CODE.matcher(mail.body());
        assertThat(matcher.find()).isTrue();
        var code = matcher.group(1);
        assertThat(mail.body()).contains("https://racha.example.com/reset-password#email=" + email.replace("@", "%40") + "&code=" + code);

        var bad = api.post("/auth/password/reset", Map.of("email", email, "code", "AAAA-AAAA", "newPassword", "another long password"), null);
        assertThat(bad.status()).isEqualTo(400);
        assertThat(bad.body().get("code").asString()).isEqualTo("invalid_recovery_code");

        // Typed carelessly: lower case, no dash, spaces around.
        var typed = " " + code.replace("-", "").toLowerCase() + " ";
        assertThat(api.post("/auth/password/reset", Map.of("email", email, "code", typed, "newPassword", "another long password"), null).status())
                .isEqualTo(204);
        assertThat(api.post("/auth/password/reset", Map.of("email", email, "code", code, "newPassword", "yet another password"), null).status())
                .as("a code works once").isEqualTo(400);
        assertThat(api.post("/auth/refresh", Map.of("refreshToken", old.refresh()), null).status()).as("every session ended").isEqualTo(401);
        assertThat(api.post("/auth/login", Map.of("email", email, "password", "another long password", "device", Api.device("x")), null).status())
                .isEqualTo(200);
    }

    @Test
    void anUnknownAddressGetsTheSameAnswerAndNoMail() {
        var before = CapturingMailer.SENT.size();
        assertThat(api.post("/auth/password/forgot", Map.of("email", "nobody-" + UUID.randomUUID() + "@example.com"), null).status()).isEqualTo(202);
        assertThat(api.post("/auth/password/forgot", Map.of("email", "not an email"), null).status()).isEqualTo(202);
        assertThat(CapturingMailer.SENT.size()).isEqualTo(before);
    }

    @Test
    void aNewCodeVoidsTheOldOneAndRequestsAreThrottled() throws InterruptedException {
        var email = "n-" + UUID.randomUUID() + "@example.com";
        api.post("/auth/register", Map.of("email", email, "password", PASSWORD, "device", Api.device("x")), null);
        api.post("/auth/password/forgot", Map.of("email", email), null);
        var first = code(CapturingMailer.awaitTo(email).body());
        CapturingMailer.SENT.removeIf(m -> m.to().equals(email));
        api.post("/auth/password/forgot", Map.of("email", email), null);
        CapturingMailer.awaitTo(email);
        assertThat(api.post("/auth/password/reset", Map.of("email", email, "code", first, "newPassword", "another long password"), null).status())
                .isEqualTo(400);
        for (int i = 0; i < 3; i++) api.post("/auth/password/forgot", Map.of("email", email), null);
        assertThat(api.post("/auth/password/forgot", Map.of("email", email), null).status()).as("5 per 15 minutes").isEqualTo(429);
        // The fifth request's code, the newest, is the one that works.
        var deadline = System.nanoTime() + 5_000_000_000L;
        while (CapturingMailer.SENT.stream().filter(m -> m.to().equals(email)).count() < 5 && System.nanoTime() < deadline) Thread.sleep(20);
        var newest = code(CapturingMailer.awaitTo(email).body());
        assertThat(api.post("/auth/password/reset", Map.of("email", email, "code", newest, "newPassword", "another long password"), null).status())
                .isEqualTo(204);
    }

    @Test
    void theRecoveryPageIsServedWithoutSigningIn() throws Exception {
        var response = java.net.http.HttpClient.newHttpClient().send(
                java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port + "/reset-password")).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("auth/password/reset");
    }

    private static String code(String body) {
        var matcher = CODE.matcher(body);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }
}
