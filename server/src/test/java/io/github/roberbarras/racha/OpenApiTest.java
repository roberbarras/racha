package io.github.roberbarras.racha;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
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
        publishedCopyIsCurrent(docs.body());
    }

    /**
     * The repository keeps a copy of the spec (docs/api/openapi.json, a §15 deliverable). It must describe the same
     * operations and schemas as the running server; `-Pracha.updateOpenApi` rewrites it.
     */
    private void publishedCopyIsCurrent(JsonNode live) {
        var file = Path.of("..", "docs", "api", "openapi.json");
        try {
            var pretty = new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(live) + "\n";
            if (Boolean.getBoolean("racha.updateOpenApi")) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, pretty);
                return;
            }
            assertThat(file).as("docs/api/openapi.json missing: run ./gradlew test -Pracha.updateOpenApi").exists();
            var published = new ObjectMapper().readTree(Files.readString(file));
            assertThat(names(published.get("paths"))).as("API paths changed: run ./gradlew test -Pracha.updateOpenApi").isEqualTo(names(live.get("paths")));
            assertThat(names(published.get("components").get("schemas"))).as("API schemas changed: run ./gradlew test -Pracha.updateOpenApi")
                    .isEqualTo(names(live.get("components").get("schemas")));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static java.util.Set<String> names(JsonNode node) {
        var names = new java.util.TreeSet<String>();
        node.propertyNames().forEach(names::add);
        return names;
    }
}
