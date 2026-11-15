package io.github.roberbarras.racha;

import static io.github.roberbarras.racha.Api.change;
import static io.github.roberbarras.racha.Api.push;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class TelemetryAndStatsApiTest {
    @Value("${local.server.port}")
    int port;

    @Test
    void eventsAndErrorsAreStoredOnceEach() {
        var api = new Api(port);
        var s = api.register("Móvil");
        var id = UUID.randomUUID().toString();
        var event = Map.of("id", id, "type", "pip", "occurredAt", "2026-11-15T10:00:00Z", "properties", Map.of("elementId", "el-1"));
        var first = api.post("/analytics/events", List.of(event, Map.of("id", UUID.randomUUID().toString(), "occurredAt", "2026-11-15T10:00:00Z")), s.access());
        assertThat(first.status()).isEqualTo(202);
        assertThat(first.body().get("accepted").asInt()).isEqualTo(1);
        assertThat(first.body().get("rejected").asInt()).isEqualTo(1); // no type
        assertThat(api.post("/analytics/events", List.of(event), s.access()).status()).isEqualTo(202); // resent: harmless

        var error = Map.of("id", UUID.randomUUID().toString(), "platform", "android", "appVersion", "0.0.1", "message", "boom",
                "stackTrace", "java.lang.IllegalStateException: boom\n\tat x", "occurredAt", "2026-11-15T10:00:00Z", "context", Map.of());
        assertThat(api.post("/errors", List.of(error), s.access()).body().get("accepted").asInt()).isEqualTo(1);
        assertThat(api.post("/errors", List.of(), null).status()).isEqualTo(401);
    }

    @Test
    void personalStats() {
        var api = new Api(port);
        var s = api.register("Móvil");
        var today = LocalDate.now(ZoneOffset.UTC);
        var yesterday = today.minusDays(1);
        api.post("/sync/push", push(List.of(
                change("progress", "a", "2026-11-15T10:00:00Z", Map.of("positionSec", 2710, "completed", true)),
                change("progress", "b", "2026-11-15T10:00:00Z", Map.of("positionSec", 300, "completed", false)),
                change("history", "h1", "2026-11-15T10:00:00Z", Map.of("elementId", "a", "watchedAt", yesterday + "T20:00:00Z", "positionSec", 2710)),
                change("history", "h2", "2026-11-15T10:00:00Z", Map.of("elementId", "b", "watchedAt", today + "T08:00:00Z", "positionSec", 300)))), s.access());
        api.post("/analytics/events", List.of(
                Map.of("id", UUID.randomUUID().toString(), "type", "watch", "occurredAt", today + "T08:05:00Z", "properties", Map.of("seconds", 300)),
                Map.of("id", UUID.randomUUID().toString(), "type", "watch", "occurredAt", today + "T08:10:00Z", "properties", Map.of("seconds", 60))), s.access());

        var stats = api.get("/stats/me?zone=UTC", s.access());
        assertThat(stats.status()).isEqualTo(200);
        assertThat(stats.body().get("completedElements").asLong()).isEqualTo(1);
        assertThat(stats.body().get("elementsInProgress").asLong()).isEqualTo(1);
        assertThat(stats.body().get("historyEntries").asLong()).isEqualTo(2);
        assertThat(stats.body().get("activeDays").asInt()).isEqualTo(2);
        assertThat(stats.body().get("streakDays").asInt()).isEqualTo(2);
        assertThat(stats.body().get("watchSeconds").asLong()).isEqualTo(360);
        assertThat(api.get("/stats/me?zone=Mars/Olympus", s.access()).status()).isEqualTo(400);
    }
}
