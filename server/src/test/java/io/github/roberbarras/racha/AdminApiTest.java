package io.github.roberbarras.racha;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The admin API (H13): who is admin, what admins see and the safeguards. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"racha.auth.admin-email=boss@example.com", "racha.metrics-token=metrics-secret-for-tests"})
@Import(TestcontainersConfiguration.class)
class AdminApiTest {
    private static final String PASSWORD = "correct horse battery";
    private static final Path DIR;

    static {
        try {
            DIR = Files.createTempDirectory("racha-admin-test");
            Files.createDirectories(DIR.resolve("backups"));
            Files.write(DIR.resolve("backups/racha-20261116-033000.dump"), new byte[1234]);
            Files.write(DIR.resolve("backups/racha-20261117-033000.dump"), new byte[2048]);
            Files.write(DIR.resolve("backups/notes.txt"), new byte[1]);
            // Earlier lines of the log file, as Spring Boot writes them (ECS JSON); the server appends its own.
            Files.writeString(DIR.resolve("racha.log"), """
                    {"@timestamp":"2026-11-17T10:00:00Z","log":{"level":"INFO","logger":"a.B"},"message":"started"}
                    {"@timestamp":"2026-11-17T10:00:01Z","log.level":"WARN","log.logger":"a.C","message":"disk almost full"}
                    {"@timestamp":"2026-11-17T10:00:02Z","log":{"level":"ERROR","logger":"a.D"},"message":"boom","error":{"type":"java.io.IOException","message":"broken"}}
                    """);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void folders(DynamicPropertyRegistry registry) {
        registry.add("racha.backup-dir", () -> DIR.resolve("backups").toString());
        registry.add("racha.log-file", () -> DIR.resolve("racha.log").toString());
    }

    @Value("${local.server.port}")
    int port;
    Api api;

    @BeforeEach
    void client() {
        api = new Api(port);
    }

    private Api.Session boss() {
        var r = api.post("/auth/register", Map.of("email", "boss@example.com", "password", PASSWORD, "device", Api.device("x")), null);
        return r.status() == 201 ? Api.session(r.body()) : api.login("boss@example.com", PASSWORD, "x");
    }

    @Test
    void onlyTheConfiguredAdminGetsIn() {
        var someone = api.register("x");
        assertThat(api.get("/admin/overview", someone.access()).status()).isEqualTo(403);
        assertThat(api.get("/admin/overview", null).status()).isEqualTo(401);
        var boss = boss();
        assertThat(api.get("/account/me", boss.access()).body().get("role").asString()).isEqualTo("admin");
        assertThat(api.get("/account/me", someone.access()).body().get("role").asString()).isEqualTo("user");

        var overview = api.get("/admin/overview", boss.access());
        assertThat(overview.status()).isEqualTo(200);
        assertThat(overview.body().get("counts").get("users").asLong()).isGreaterThanOrEqualTo(2);
        assertThat(overview.body().get("counts").get("databaseBytes").asLong()).isPositive();
        assertThat(overview.body().get("server").get("mailConfigured").asBoolean()).isFalse();
        assertThat(overview.body().get("server").get("metricsTokenSet").asBoolean()).isTrue();
        assertThat(overview.body().get("metrics").get("heapUsedBytes").asDouble()).isPositive();
        assertThat(overview.body().get("backupsBytes").asLong()).isEqualTo(1234 + 2048);
    }

    @Test
    void backupsAndLogsAreListed() {
        var boss = boss();
        var backups = api.get("/admin/backups", boss.access()).body();
        assertThat(backups.size()).isEqualTo(2);
        assertThat(backups.get(0).get("name").asString()).isEqualTo("racha-20261117-033000.dump");

        var warnings = api.get("/admin/logs?level=WARN&lines=50", boss.access()).body();
        assertThat(warnings.size()).isGreaterThanOrEqualTo(2);
        var messages = new java.util.ArrayList<String>();
        warnings.forEach(e -> messages.add(e.get("level").asString() + " " + e.get("message").asString()));
        assertThat(messages).contains("WARN disk almost full", "ERROR boom");
        assertThat(messages).noneMatch(m -> m.startsWith("INFO"));
        assertThat(api.get("/admin/logs?level=LOUD", boss.access()).status()).isEqualTo(400);
    }

    @Test
    void roleAndAccessChangesKeepAnActiveAdmin() {
        var boss = boss();
        var someone = api.register("Móvil");
        var users = api.get("/admin/users", boss.access()).body();
        var bossId = boss.userId().toString();
        assertThat(users.findValuesAsString("email")).contains("boss@example.com");

        // The only admin cannot stop being one, nor lock themselves out.
        var demote = api.post("/admin/users/" + bossId + "/role", Map.of("role", "user"), boss.access());
        assertThat(demote.status()).isEqualTo(409);
        assertThat(demote.body().get("code").asString()).isEqualTo("last_admin");
        assertThat(api.post("/admin/users/" + bossId + "/disabled", Map.of("disabled", true), boss.access()).status()).isEqualTo(409);

        // Promoted, the other one gets in at once (the role is read on every request).
        assertThat(api.post("/admin/users/" + someone.userId() + "/role", Map.of("role", "admin"), boss.access()).status()).isEqualTo(204);
        assertThat(api.get("/admin/overview", someone.access()).status()).isEqualTo(200);
        assertThat(api.post("/admin/users/" + someone.userId() + "/role", Map.of("role", "user"), boss.access()).status()).isEqualTo(204);
        assertThat(api.get("/admin/overview", someone.access()).status()).isEqualTo(403);

        // Disabled: every device signed out, no new sign-ins; enabled again, it works.
        assertThat(api.post("/admin/users/" + someone.userId() + "/disabled", Map.of("disabled", true), boss.access()).status()).isEqualTo(204);
        assertThat(api.post("/auth/refresh", Map.of("refreshToken", someone.refresh()), null).status()).isEqualTo(401);
        assertThat(api.post("/admin/users/" + UUID.randomUUID() + "/role", Map.of("role", "admin"), boss.access()).status()).isEqualTo(404);
    }

    @Test
    void mailTestSaysWhyItCannotSend() {
        var r = api.post("/admin/mail/test", null, boss().access());
        assertThat(r.status()).isEqualTo(502);
        assertThat(r.body().get("code").asString()).isEqualTo("mail_failed");
    }

    @Test
    void prometheusNeedsItsToken() throws Exception {
        var http = HttpClient.newHttpClient();
        var url = URI.create("http://localhost:" + port + "/actuator/prometheus");
        assertThat(http.send(HttpRequest.newBuilder(url).build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isIn(401, 403);
        var withAdminJwt = HttpRequest.newBuilder(url).header("Authorization", "Bearer " + boss().access()).build();
        assertThat(http.send(withAdminJwt, HttpResponse.BodyHandlers.ofString()).statusCode()).isIn(401, 403);
        var scrape = http.send(HttpRequest.newBuilder(url).header("Authorization", "Bearer metrics-secret-for-tests").build(), HttpResponse.BodyHandlers.ofString());
        assertThat(scrape.statusCode()).isEqualTo(200);
        assertThat(scrape.body()).contains("jvm_memory_used_bytes");
    }
}
