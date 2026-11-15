package io.github.roberbarras.racha;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** A self-hosted server can refuse new accounts (RACHA_REGISTRATION=closed). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "racha.auth.registration=closed")
@Import(TestcontainersConfiguration.class)
class ClosedRegistrationTest {
    @Value("${local.server.port}")
    int port;

    @Test
    void refusesNewAccounts() {
        var r = new Api(port).post("/auth/register", Map.of("email", "new@example.com", "password", "correct horse battery", "device", Api.device("x")), null);
        assertThat(r.status()).isEqualTo(403);
        assertThat(r.body().get("code").asString()).isEqualTo("registration_closed");
    }
}
