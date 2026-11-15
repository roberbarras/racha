package io.github.roberbarras.racha.sync.adapter.web;

import io.github.roberbarras.racha.shared.adapter.web.ApiErrors;
import io.github.roberbarras.racha.shared.adapter.web.Caller;
import io.github.roberbarras.racha.sync.application.SyncService;
import io.github.roberbarras.racha.sync.domain.EntityType;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;

/** Shared behaviour of the §7.3 collection endpoints: views over the sync store with the same rules. */
public final class Collections {
    private final SyncService sync;
    private final ChangeReader reader;

    public Collections(SyncService sync, ChangeReader reader) {
        this.sync = sync;
        this.reader = reader;
    }

    SyncDtos.PageResponse list(Jwt jwt, EntityType type, long since, int limit) {
        return SyncDtos.PageResponse.of(sync.list(Caller.of(jwt).userId(), type, since, limit));
    }

    ResponseEntity<?> get(Jwt jwt, EntityType type, String id) {
        return sync.get(Caller.of(jwt).userId(), type, id)
                .<ResponseEntity<?>>map(s -> ResponseEntity.ok(SyncDtos.RecordDto.of(s)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiErrors.problem(HttpStatus.NOT_FOUND, "not_found", type.wire() + " " + id + " not found")));
    }

    SyncDtos.PushResponse upsert(Jwt jwt, EntityType type, List<SyncDtos.ItemDto> items) {
        var caller = Caller.of(jwt);
        var read = reader.items(type, items);
        var result = sync.push(caller.userId(), caller.deviceId(), read.valid());
        var results = new ArrayList<>(result.results());
        results.addAll(read.rejected());
        return SyncDtos.PushResponse.of(new SyncService.PushResult(results, result.applied(), result.stale(), result.invalid() + read.rejected().size()));
    }

    /** 200 applied, 409 stale (the server's newer version in the body), 400 invalid. */
    ResponseEntity<SyncDtos.ResultDto> put(Jwt jwt, EntityType type, String id, SyncDtos.ItemDto item) {
        var response = upsert(jwt, type, List.of(new SyncDtos.ItemDto(id, item.updatedAt(), item.deleted(), item.data())));
        return single(response);
    }

    ResponseEntity<SyncDtos.ResultDto> delete(Jwt jwt, EntityType type, String id) {
        var caller = Caller.of(jwt);
        var result = sync.delete(caller.userId(), caller.deviceId(), type, id);
        return single(SyncDtos.PushResponse.of(result));
    }

    private static ResponseEntity<SyncDtos.ResultDto> single(SyncDtos.PushResponse response) {
        var result = response.results().getFirst();
        var status = switch (result.outcome()) {
            case "applied" -> HttpStatus.OK;
            case "stale" -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(result);
    }
}
