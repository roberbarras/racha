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

    /** Event types whose properties the statistics views read (V5); their shape is checked on arrival. */
    public static final String WATCH = "watch";

    public static final long MAX_WATCH_SECONDS = 86_400;

    /**
     * Whether the event can be stored. A "watch" event (one viewing session, §8) needs `seconds` (0..24 h) and, when
     * present, numeric `positionSec`/`playbackSpeed`, boolean `completed`, ISO instants `startedAt`/`endedAt` and
     * string ids; anything else is kept as received.
     */
    public static boolean wellFormed(Event e) {
        if (e.id() == null || e.occurredAt() == null || e.type() == null || e.type().isBlank() || e.type().length() > MAX_TYPE_LENGTH) return false;
        if (!WATCH.equals(e.type())) return true;
        var p = e.properties() == null ? Map.<String, Object>of() : e.properties();
        if (!(p.get("seconds") instanceof Number seconds) || seconds.doubleValue() < 0 || seconds.doubleValue() > MAX_WATCH_SECONDS) return false;
        return optional(p, "positionSec", Number.class) && optional(p, "playbackSpeed", Number.class) && optional(p, "completed", Boolean.class)
                && optional(p, "elementId", String.class) && optional(p, "itemId", String.class) && optional(p, "categoryId", String.class)
                && optional(p, "subtitlesLang", String.class) && instant(p.get("startedAt")) && instant(p.get("endedAt"));
    }

    private static boolean optional(Map<String, Object> p, String key, Class<?> type) {
        var value = p.get(key);
        return value == null || type.isInstance(value);
    }

    private static boolean instant(Object value) {
        if (value == null) return true;
        if (!(value instanceof String text)) return false;
        try {
            Instant.parse(text);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    public record ClientError(UUID id, String platform, String appVersion, String message, String stackTrace, Instant occurredAt, Map<String, Object> context) {}

    public interface Store {
        /** Number of rows actually inserted (duplicates are ignored). */
        int saveEvents(UUID userId, UUID deviceId, List<Event> events, Instant receivedAt);

        int saveErrors(UUID userId, UUID deviceId, List<ClientError> errors, Instant receivedAt);
    }
}
