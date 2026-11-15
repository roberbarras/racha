package io.github.roberbarras.racha.sync.application;

import io.github.roberbarras.racha.sync.domain.EntityType;
import io.github.roberbarras.racha.sync.domain.SyncModel;
import io.github.roberbarras.racha.sync.domain.SyncRules;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** §7.3–7.4: push and pull of changes, and the per-collection REST views over the same store (ADR-0012). */
public final class SyncService {
    public static final int MAX_PAGE = 1000;

    public record PushResult(List<SyncModel.Result> results, int applied, int stale, int invalid) {}

    private final SyncModel.Store store;
    private final SyncModel.Audit audit;
    private final Clock clock;
    private final int maxBatch;

    public SyncService(SyncModel.Store store, SyncModel.Audit audit, Clock clock, int maxBatch) {
        this.store = store;
        this.audit = audit;
        this.clock = clock;
        this.maxBatch = maxBatch;
    }

    public PushResult push(UUID userId, UUID deviceId, List<SyncModel.Change> changes) {
        if (changes.size() > maxBatch) throw new IllegalArgumentException("at most " + maxBatch + " changes per push");
        var now = clock.instant();
        var results = new ArrayList<SyncModel.Result>(changes.size());
        for (var change : changes) {
            var problem = SyncRules.problem(change);
            results.add(problem.isPresent()
                    ? SyncModel.Result.invalid(change.entity(), change.id(), problem.get())
                    : store.apply(userId, change.withUpdatedAt(SyncRules.clamp(change.updatedAt(), now))));
        }
        int applied = count(results, SyncModel.Outcome.APPLIED);
        int stale = count(results, SyncModel.Outcome.STALE);
        int invalid = count(results, SyncModel.Outcome.INVALID);
        audit.record(userId, deviceId, now, applied, stale, invalid);
        return new PushResult(results, applied, stale, invalid);
    }

    public SyncModel.Page pull(UUID userId, long since, int limit) {
        return store.pull(userId, Math.max(0, since), clampLimit(limit));
    }

    public SyncModel.Page list(UUID userId, EntityType type, long since, int limit) {
        return store.list(userId, type, Math.max(0, since), clampLimit(limit), false);
    }

    public Optional<SyncModel.Stored> get(UUID userId, EntityType type, String id) {
        return store.get(userId, type, id).filter(s -> !s.deleted());
    }

    /** A REST delete is a tombstone, so it reaches the user's other devices. */
    public PushResult delete(UUID userId, UUID deviceId, EntityType type, String id) {
        return push(userId, deviceId, List.of(new SyncModel.Change(type, id, clock.instant(), true, Map.of())));
    }

    private static int clampLimit(int limit) {
        return Math.min(Math.max(limit, 1), MAX_PAGE);
    }

    private static int count(List<SyncModel.Result> results, SyncModel.Outcome outcome) {
        return (int) results.stream().filter(r -> r.outcome() == outcome).count();
    }
}
