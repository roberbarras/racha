package io.github.roberbarras.racha.telemetry.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** §8: usage events and client errors, kept as received for the future admin panel. */
public final class Telemetry {
    public static final int MAX_TYPE_LENGTH = 100;
    public static final int MAX_MESSAGE_LENGTH = 2_000;
    public static final int MAX_STACK_LENGTH = 32_000;

    private Telemetry() {}

    /** [id] is chosen by the client, so a batch sent twice is stored once. */
    public record Event(UUID id, String type, Instant occurredAt, Map<String, Object> properties) {}

    public record ClientError(UUID id, String platform, String appVersion, String message, String stackTrace, Instant occurredAt, Map<String, Object> context) {}

    public interface Store {
        /** Number of rows actually inserted (duplicates are ignored). */
        int saveEvents(UUID userId, UUID deviceId, List<Event> events, Instant receivedAt);

        int saveErrors(UUID userId, UUID deviceId, List<ClientError> errors, Instant receivedAt);
    }
}
