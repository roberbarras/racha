package io.github.roberbarras.racha.sync.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** Validation of incoming changes and the clock rule of ADR-0012. */
public final class SyncRules {
    /** A device whose clock runs ahead would otherwise win every conflict for as long as it is ahead. */
    public static final Duration MAX_CLOCK_AHEAD = Duration.ofMinutes(5);
    public static final int MAX_ID_LENGTH = 200;
    private static final Pattern SETTING_ID = Pattern.compile("^(user|device:[0-9a-fA-F-]{36}):[A-Za-z0-9._-]{1,100}$");

    private SyncRules() {}

    public static Instant clamp(Instant updatedAt, Instant now) {
        return updatedAt.isAfter(now.plus(MAX_CLOCK_AHEAD)) ? now : updatedAt;
    }

    /** Why [change] cannot be stored, or empty if it is valid. */
    public static Optional<String> problem(SyncModel.Change change) {
        if (change.id() == null || change.id().isBlank() || change.id().length() > MAX_ID_LENGTH) return Optional.of("id is required (max 200 characters)");
        if (change.updatedAt() == null) return Optional.of("updatedAt is required");
        if (change.entity() == EntityType.SETTING && !SETTING_ID.matcher(change.id()).matches()) {
            return Optional.of("setting id must be user:<key> or device:<deviceId>:<key>");
        }
        if (change.deleted()) return Optional.empty();
        var data = change.data();
        if (data == null) return Optional.of("data is required");
        return switch (change.entity()) {
            case CATEGORY_ROOT, LIBRARY_ITEM -> text(data, "categoryId");
            case SECTION, ELEMENT -> text(data, "itemId");
            case SUBTITLE, BOOKMARK -> text(data, "elementId");
            case PROGRESS -> nonNegative(data, "positionSec", true).or(() -> nonNegative(data, "durationSec", false)).or(() -> bool(data, "completed"));
            case HISTORY -> text(data, "elementId").or(() -> instant(data, "watchedAt")).or(() -> nonNegative(data, "positionSec", false));
            case SETTING -> data.containsKey("value") ? Optional.empty() : Optional.of("data.value is required");
            case CATEGORY, FAVORITE -> Optional.empty();
        };
    }

    /** For per-device settings, the device their id names. */
    public static Optional<UUID> settingDevice(String id) {
        return id.startsWith("device:") ? Optional.of(UUID.fromString(id.substring(7, 43))) : Optional.empty();
    }

    public static Long longValue(Map<String, Object> data, String field) {
        return data != null && data.get(field) instanceof Number n ? n.longValue() : null;
    }

    private static Optional<String> text(Map<String, Object> data, String field) {
        return data.get(field) instanceof String s && !s.isBlank() ? Optional.empty() : Optional.of("data." + field + " is required");
    }

    private static Optional<String> nonNegative(Map<String, Object> data, String field, boolean required) {
        var value = data.get(field);
        if (value == null) return required ? Optional.of("data." + field + " is required") : Optional.empty();
        return value instanceof Number n && n.doubleValue() >= 0 ? Optional.empty() : Optional.of("data." + field + " must be a number ≥ 0");
    }

    private static Optional<String> bool(Map<String, Object> data, String field) {
        var value = data.get(field);
        return value == null || value instanceof Boolean ? Optional.empty() : Optional.of("data." + field + " must be true or false");
    }

    private static Optional<String> instant(Map<String, Object> data, String field) {
        if (!(data.get(field) instanceof String s)) return Optional.of("data." + field + " is required (ISO-8601 instant)");
        try {
            Instant.parse(s);
            return Optional.empty();
        } catch (DateTimeParseException e) {
            return Optional.of("data." + field + " must be an ISO-8601 instant");
        }
    }
}
