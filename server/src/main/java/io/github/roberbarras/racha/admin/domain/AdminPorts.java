package io.github.roberbarras.racha.admin.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** What the admin screen reads: figures from the database, backup files, the log file and runtime metrics. */
public final class AdminPorts {
    private AdminPorts() {}

    /** Counts over the whole server; "active" = seen in the last 30 days, "recent" = last 7 days. */
    public record Counts(
            long users, long admins, long disabledUsers, long activeUsers, long devices, long activeDevices, long activeSessions,
            long progressRows, long historyRows, long recentEvents, long recentErrors, long databaseBytes) {}

    public record UserSummary(
            UUID id, String email, String displayName, String role, boolean disabled, Instant createdAt, Instant lastSeenAt, int devices,
            int activeSessions) {}

    public record BackupFile(String name, long bytes, Instant createdAt) {}

    /** One log event; [error] is the exception's first line, if any. */
    public record LogEntry(Instant at, String level, String logger, String message, String error) {}

    /** What the apps report, server-wide (§8): who is active, what is used, what breaks. */
    public record Activity(
            List<DayCount> activeUsersByDay, List<UserHours> watchHoursByUser, List<FeatureUse> features, List<VersionErrors> errorsByVersion,
            List<ClientError> recentErrors, List<SyncFailure> recentSyncFailures) {}

    public record DayCount(java.time.LocalDate day, long count) {}

    public record UserHours(String email, double hours, long sessions) {}

    public record FeatureUse(String feature, long uses, long users, Instant lastUsedAt) {}

    public record VersionErrors(String platform, String appVersion, long errors, long users, Instant lastAt) {}

    public record ClientError(Instant at, String email, String platform, String appVersion, String message) {}

    public record SyncFailure(Instant at, String email, String kind, String detail) {}

    public interface Queries {
        /** Server-wide activity; per-day counts since [since]. */
        Activity activity(Instant since);

        Counts counts(Instant now);

        List<UserSummary> users(Instant now);

        void setRole(UUID userId, String role, Instant now);

        /** Disabling also ends every session of the account. */
        void setDisabled(UUID userId, boolean disabled, Instant now);
    }

    public interface Backups {
        /** Where they are read from; null when the server was not given the backups folder. */
        String location();

        /** Newest first. */
        List<BackupFile> list();
    }

    public interface Logs {
        String location();

        /** The newest [max] entries at [minLevel] or above, newest first. */
        List<LogEntry> tail(int max, String minLevel);
    }

    public interface Metrics {
        /** Name → value (memory in bytes, CPU 0–1, durations in seconds). */
        Map<String, Double> snapshot();
    }
}
