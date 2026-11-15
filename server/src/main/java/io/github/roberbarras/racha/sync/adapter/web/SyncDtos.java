package io.github.roberbarras.racha.sync.adapter.web;

import io.github.roberbarras.racha.sync.application.SyncService;
import io.github.roberbarras.racha.sync.domain.SyncModel;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** JSON shapes of the sync API. */
public final class SyncDtos {
    private SyncDtos() {}

    /** One change; [entity] is the wire name (category, libraryItem, element, progress, bookmark, history, …). */
    public record ChangeDto(String entity, String id, Instant updatedAt, Boolean deleted, Map<String, Object> data) {
        /** Optional on the wire: absent means a regular change. */
        public boolean isDeleted() {
            return Boolean.TRUE.equals(deleted);
        }
    }

    public record PushRequest(List<ChangeDto> changes) {}

    public record RecordDto(String entity, String id, Instant updatedAt, boolean deleted, long seq, Map<String, Object> data) {
        static RecordDto of(SyncModel.Stored s) {
            return s == null ? null : new RecordDto(s.entity().wire(), s.id(), s.updatedAt(), s.deleted(), s.seq(), s.data());
        }
    }

    /** outcome: applied | stale (take [server]) | invalid ([reason]). */
    public record ResultDto(String entity, String id, String outcome, String reason, RecordDto server) {
        static ResultDto of(SyncModel.Result r) {
            return new ResultDto(r.entity() == null ? null : r.entity().wire(), r.id(), r.outcome().name().toLowerCase(java.util.Locale.ROOT),
                    r.reason(), RecordDto.of(r.server()));
        }
    }

    public record PushResponse(List<ResultDto> results, int applied, int stale, int invalid) {
        static PushResponse of(SyncService.PushResult r) {
            return new PushResponse(r.results().stream().map(ResultDto::of).toList(), r.applied(), r.stale(), r.invalid());
        }
    }

    /** [cursor] goes into the next `since`; keep pulling while [hasMore]. */
    public record PageResponse(List<RecordDto> changes, long cursor, boolean hasMore) {
        static PageResponse of(SyncModel.Page p) {
            return new PageResponse(p.changes().stream().map(RecordDto::of).toList(), p.cursor(), p.hasMore());
        }
    }

    /** A collection item for the REST views (the entity is given by the URL). */
    public record ItemDto(String id, Instant updatedAt, Boolean deleted, Map<String, Object> data) {}
}
