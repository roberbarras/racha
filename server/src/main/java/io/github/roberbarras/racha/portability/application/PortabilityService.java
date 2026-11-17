package io.github.roberbarras.racha.portability.application;

import io.github.roberbarras.racha.sync.application.SyncService;
import io.github.roberbarras.racha.sync.domain.SyncModel;
import io.github.roberbarras.racha.telemetry.domain.Telemetry;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A user's data to a file and back (H16, ADR-0015): everything synced (categories, folders, library edits, progress,
 * bookmarks, history, favourites, settings) and their viewing events, so it can be imported into another server.
 * Importing goes through the same rules as sync (newest wins) and events are stored once: it can be repeated.
 */
public final class PortabilityService {
    /** What is exported; [records] in sync's own shape, so importing is a push. */
    public record Export(List<SyncModel.Stored> records, List<Telemetry.Event> events) {}

    public record ImportReport(int applied, int stale, int invalid, int events, int duplicateEvents, int rejectedEvents) {}

    private static final int PAGE = 1000;

    private final SyncService sync;
    private final Telemetry.Store events;
    private final Clock clock;
    private final int maxBatch;

    public PortabilityService(SyncService sync, Telemetry.Store events, Clock clock, int maxBatch) {
        this.sync = sync;
        this.events = events;
        this.clock = clock;
        this.maxBatch = maxBatch;
    }

    public Export export(UUID userId) {
        var records = new ArrayList<SyncModel.Stored>();
        long cursor = 0;
        while (true) {
            var page = sync.pull(userId, cursor, PAGE);
            page.changes().stream().filter(r -> !r.deleted()).forEach(records::add);
            if (!page.hasMore() || page.cursor() == cursor) break;
            cursor = page.cursor();
        }
        return new Export(records, events.eventsOf(userId));
    }

    /** [changes] already read and checked by the web adapter ([rejected] of them were not); applied in sync batches. */
    public ImportReport importData(UUID userId, UUID deviceId, List<SyncModel.Change> changes, int rejected, List<Telemetry.Event> eventsIn) {
        int applied = 0, stale = 0, invalid = rejected;
        for (int i = 0; i < changes.size(); i += maxBatch) {
            var result = sync.push(userId, deviceId, changes.subList(i, Math.min(changes.size(), i + maxBatch)));
            applied += result.applied();
            stale += result.stale();
            invalid += result.invalid();
        }
        // Events keep their ids: imported twice, or back into the server they came from, they are stored once.
        var wellFormed = eventsIn.stream().filter(Telemetry::wellFormed).toList();
        int stored = 0;
        for (int i = 0; i < wellFormed.size(); i += maxBatch) {
            stored += events.saveEvents(userId, null, wellFormed.subList(i, Math.min(wellFormed.size(), i + maxBatch)), clock.instant());
        }
        return new ImportReport(applied, stale, invalid, stored, wellFormed.size() - stored, eventsIn.size() - wellFormed.size());
    }
}
