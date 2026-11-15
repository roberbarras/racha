package io.github.roberbarras.racha.telemetry.application;

import io.github.roberbarras.racha.telemetry.domain.Telemetry;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

/** §7.3 `/analytics/events` and `/errors` (batches). Invalid entries are dropped and counted, never fatal. */
public final class TelemetryService {
    public record Accepted(int accepted, int rejected) {}

    private final Telemetry.Store store;
    private final Clock clock;
    private final int maxBatch;

    public TelemetryService(Telemetry.Store store, Clock clock, int maxBatch) {
        this.store = store;
        this.clock = clock;
        this.maxBatch = maxBatch;
    }

    public Accepted events(UUID userId, UUID deviceId, List<Telemetry.Event> events) {
        check(events.size());
        var valid = events.stream()
                .filter(e -> e.id() != null && e.occurredAt() != null && e.type() != null && !e.type().isBlank() && e.type().length() <= Telemetry.MAX_TYPE_LENGTH)
                .toList();
        store.saveEvents(userId, deviceId, valid, clock.instant());
        return new Accepted(valid.size(), events.size() - valid.size());
    }

    public Accepted errors(UUID userId, UUID deviceId, List<Telemetry.ClientError> errors) {
        check(errors.size());
        var valid = errors.stream()
                .filter(e -> e.id() != null && e.occurredAt() != null && e.message() != null && !e.message().isBlank())
                .map(e -> new Telemetry.ClientError(e.id(), e.platform(), e.appVersion(), cut(e.message(), Telemetry.MAX_MESSAGE_LENGTH),
                        cut(e.stackTrace(), Telemetry.MAX_STACK_LENGTH), e.occurredAt(), e.context()))
                .toList();
        store.saveErrors(userId, deviceId, valid, clock.instant());
        return new Accepted(valid.size(), errors.size() - valid.size());
    }

    private void check(int size) {
        if (size > maxBatch) throw new IllegalArgumentException("at most " + maxBatch + " entries per batch");
    }

    private static String cut(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
