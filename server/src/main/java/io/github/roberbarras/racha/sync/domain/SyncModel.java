package io.github.roberbarras.racha.sync.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Values of the sync protocol. `data` is the client's JSON object as plain maps/lists/strings/numbers/booleans. */
public final class SyncModel {
    private SyncModel() {}

    /** A change made on a device: last writer (by [updatedAt]) wins. */
    public record Change(EntityType entity, String id, Instant updatedAt, boolean deleted, Map<String, Object> data) {
        public Change withUpdatedAt(Instant at) {
            return new Change(entity, id, at, deleted, data);
        }
    }

    /** What the server holds for an entity, with the sequence number that orders pulls. */
    public record Stored(EntityType entity, String id, Instant updatedAt, boolean deleted, long seq, Map<String, Object> data) {}

    public enum Outcome { APPLIED, STALE, INVALID }

    /** [server] is the version the server keeps (for STALE: the newer one the client should take). */
    public record Result(EntityType entity, String id, Outcome outcome, String reason, Stored server) {
        public static Result invalid(EntityType entity, String id, String reason) {
            return new Result(entity, id, Outcome.INVALID, reason, null);
        }
    }

    public record Page(List<Stored> changes, long cursor, boolean hasMore) {}

    /** Outbound port: the per-entity store (ADR-0012). */
    public interface Store {
        /** Applies an already validated change with last-writer-wins. */
        Result apply(UUID userId, Change change);

        /** Every entity changed after [since], in sequence order. */
        Page pull(UUID userId, long since, int limit);

        /** One entity type changed after [since]. */
        Page list(UUID userId, EntityType type, long since, int limit, boolean includeDeleted);

        Optional<Stored> get(UUID userId, EntityType type, String id);
    }

    /** Outbound port: audit of pushes (§6.2 `sync_events`). */
    public interface Audit {
        void record(UUID userId, UUID deviceId, Instant at, int applied, int stale, int invalid);
    }
}
