package io.github.roberbarras.racha.telemetry.adapter.persistence;

import io.github.roberbarras.racha.telemetry.domain.Telemetry;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

public final class JdbcTelemetryStore implements Telemetry.Store {
    private final JdbcClient jdbc;
    private final JsonMapper json;

    public JdbcTelemetryStore(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public int saveEvents(UUID userId, UUID deviceId, List<Telemetry.Event> events, Instant receivedAt) {
        int inserted = 0;
        for (var e : events) {
            inserted += jdbc.sql("""
                    insert into analytics_events (id, user_id, device_id, type, occurred_at, received_at, properties)
                    values (:id, :user, :device, :type, :occurred, :received, cast(:properties as jsonb)) on conflict (id) do nothing""")
                    .param("id", e.id()).param("user", userId).param("device", deviceId).param("type", e.type())
                    .param("occurred", e.occurredAt().atOffset(ZoneOffset.UTC)).param("received", receivedAt.atOffset(ZoneOffset.UTC))
                    .param("properties", json.writeValueAsString(e.properties() == null ? Map.of() : e.properties()))
                    .update();
        }
        return inserted;
    }

    @Override
    public int saveErrors(UUID userId, UUID deviceId, List<Telemetry.ClientError> errors, Instant receivedAt) {
        int inserted = 0;
        for (var e : errors) {
            inserted += jdbc.sql("""
                    insert into errors (id, user_id, device_id, platform, app_version, message, stack_trace, occurred_at, received_at, context)
                    values (:id, :user, :device, :platform, :version, :message, :stack, :occurred, :received, cast(:context as jsonb))
                    on conflict (id) do nothing""")
                    .param("id", e.id()).param("user", userId).param("device", deviceId).param("platform", e.platform())
                    .param("version", e.appVersion()).param("message", e.message()).param("stack", e.stackTrace())
                    .param("occurred", e.occurredAt().atOffset(ZoneOffset.UTC)).param("received", receivedAt.atOffset(ZoneOffset.UTC))
                    .param("context", json.writeValueAsString(e.context() == null ? Map.of() : e.context()))
                    .update();
        }
        return inserted;
    }
}
