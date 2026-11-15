package io.github.roberbarras.racha;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** The API is described (OpenAPI 3) without a token, and lists every §7.3 endpoint. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class OpenApiTest {
    @Value("${local.server.port}")
    int port;

    @Test
    void describesTheApi() {
        var docs = new Api(port).get("/v3/api-docs", null);
        assertThat(docs.status()).isEqualTo(200);
        var paths = docs.body().get("paths");
        for (var path : new String[] {"/auth/register", "/auth/login", "/auth/refresh", "/categories", "/library-items", "/elements",
                "/progress", "/bookmarks", "/favorites", "/history", "/settings", "/sync/push", "/sync/pull", "/devices/register",
                "/analytics/events", "/errors", "/stats/me"}) {
            assertThat(paths.has(path)).as(path).isTrue();
        }
        assertThat(docs.body().get("components").get("securitySchemes").has("bearer")).isTrue();
    }
}
