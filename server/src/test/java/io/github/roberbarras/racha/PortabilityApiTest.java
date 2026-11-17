package io.github.roberbarras.racha;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** A user's data to a file and into another server (H16, ADR-0015). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class PortabilityApiTest {
    @Value("${local.server.port}")
    int port;
    @Autowired
    JdbcClient jdbc;
    Api api;
    final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void client() {
        api = new Api(port);
    }

    private JsonNode download(String token) throws Exception {
        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/account/export"))
                .header("Authorization", "Bearer " + token).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Disposition").orElse("")).startsWith("attachment").contains("racha-").contains(".json");
        return json.readTree(response.body());
    }

    @Test
    void everythingTravelsToAnotherServerAndImportingTwiceChangesNothing() throws Exception {
        var old = api.register("Portátil");
        var today = LocalDate.now(ZoneOffset.UTC);
        api.post("/sync/push", Map.of("changes", List.of(
                Api.change("category", "courses", "2026-11-15T10:00:00Z", Map.of("name", "Cursos")),
                Api.change("libraryItem", "aws", "2026-11-15T10:00:00Z", Map.of("categoryId", "courses", "title", "AWS", "titleOverride", "AWS SAA")),
                Api.change("element", "e1", "2026-11-15T10:00:00Z", Map.of("itemId", "aws", "title", "Intro", "durationSec", 600)),
                Api.change("progress", "e1", "2026-11-15T10:00:00Z", Map.of("positionSec", 600, "completed", true)),
                Api.change("bookmark", "b1", "2026-11-15T10:00:00Z", Map.of("elementId", "e1", "positionSec", 42, "note", "importante")),
                Api.change("history", "h1", "2026-11-15T10:00:00Z", Map.of("elementId", "e1", "watchedAt", today + "T20:00:00Z", "positionSec", 600)),
                Api.change("favorite", "item:aws", "2026-11-15T10:00:00Z", Map.of("targetType", "item", "targetId", "aws")),
                Api.change("setting", "user:player.defaultSpeed", "2026-11-15T10:00:00Z", Map.of("value", "1.5")))), old.access());
        var p = new HashMap<String, Object>(Map.of("elementId", "e1", "itemId", "aws", "categoryId", "courses", "seconds", 600, "completed", true));
        api.post("/analytics/events", List.of(Map.of("id", UUID.randomUUID().toString(), "type", "watch", "occurredAt", today + "T20:10:00Z", "properties", p)),
                old.access());

        var file = download(old.access());
        assertThat(file.get("format").asString()).isEqualTo("racha-backup");
        assertThat(file.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(file.get("records").findValuesAsString("entity"))
                .contains("category", "libraryItem", "element", "progress", "bookmark", "history", "favorite", "setting");
        assertThat(file.get("events").size()).isEqualTo(1);

        // "Another server": the old account and everything it had are gone; a new account imports the file.
        jdbc.sql("delete from users where id = ?").param(old.userId()).update();
        var fresh = api.register("Portátil nuevo");
        var imported = api.post("/account/import", file, fresh.access());
        assertThat(imported.status()).isEqualTo(200);
        assertThat(imported.body().get("applied").asInt()).isEqualTo(file.get("records").size());
        assertThat(imported.body().get("invalid").asInt()).isZero();
        assertThat(imported.body().get("events").asInt()).isEqualTo(1);

        var pulled = api.get("/sync/pull?since=0", fresh.access()).body().get("changes");
        assertThat(pulled.findValuesAsString("entity")).contains("progress", "bookmark", "history", "favorite", "setting");
        var stats = api.get("/stats/me?zone=UTC", fresh.access()).body();
        assertThat(stats.get("watchSeconds").asLong()).isEqualTo(600);
        assertThat(stats.get("completedElements").asLong()).isEqualTo(1);
        var details = api.get("/stats/me/details?zone=UTC", fresh.access()).body();
        assertThat(details.get("titles").get(0).get("title").asString()).isEqualTo("AWS SAA");

        // The same data again: equal timestamps are re-applied by sync's rule, but nothing changes or doubles.
        var countBefore = api.get("/sync/pull?since=0&limit=1000", fresh.access()).body().get("changes").size();
        var again = api.post("/account/import", file, fresh.access()).body();
        assertThat(again.get("invalid").asInt()).isZero();
        assertThat(api.get("/sync/pull?since=0&limit=1000", fresh.access()).body().get("changes").size()).isEqualTo(countBefore);
        assertThat(again.get("events").asInt()).isZero();
        assertThat(again.get("duplicateEvents").asInt()).isEqualTo(1);
        assertThat(api.get("/stats/me?zone=UTC", fresh.access()).body().get("watchSeconds").asLong()).as("not counted twice").isEqualTo(600);
    }

    @Test
    void otherFilesAreRefusedWithoutTouchingAnything() {
        var s = api.register("x");
        var config = api.post("/account/import", Map.of("format", "racha-config", "schemaVersion", 1), s.access());
        assertThat(config.status()).isEqualTo(400);
        var newer = api.post("/account/import", Map.of("format", "racha-backup", "schemaVersion", 99, "records", List.of()), s.access());
        assertThat(newer.status()).isEqualTo(400);
        assertThat(newer.body().get("detail").asString()).contains("newer");
        assertThat(api.post("/account/import", Map.of("format", "racha-backup", "schemaVersion", 1), null).status()).isEqualTo(401);
    }
    @Test
    void anAppsOwnBackupImportsIgnoringItsDevicePart() {
        var s = api.register("x");
        // As the apps write it (FullBackup): source "device", no events, plus folders and device settings.
        var file = Map.of(
                "format", "racha-backup", "schemaVersion", 1, "createdAt", "2026-11-17T20:00:00Z",
                "source", Map.of("kind", "device", "app", "Racha 1.1.0 (Linux)"),
                "records", List.of(Api.change("progress", "e9", "2026-11-17T19:00:00Z", Map.of("positionSec", 30, "completed", false))),
                "device", Map.of("roots", List.of(Map.of("key", UUID.randomUUID().toString(), "categoryKey", "courses", "location", "file:///x")),
                        "settings", Map.of("player.hardwareDecoding", "true"), "localStats", List.of(List.of("watchSeconds", "2026-11-17", 60.0))));
        var r = api.post("/account/import", file, s.access());
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().get("applied").asInt()).isEqualTo(1);
    }
}
