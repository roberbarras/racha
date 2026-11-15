package io.github.roberbarras.racha.sync.adapter.web;

import io.github.roberbarras.racha.shared.adapter.web.Caller;
import io.github.roberbarras.racha.sync.application.SyncService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.ArrayList;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** §7.3 `/sync/push` and `/sync/pull` (ADR-0012). */
@RestController
@RequestMapping("/sync")
@Tag(name = "Sync", description = "Batches of local changes up, remote changes since a cursor down; last writer wins")
public class SyncController {
    private final SyncService sync;
    private final ChangeReader reader;

    public SyncController(SyncService sync, ChangeReader reader) {
        this.sync = sync;
        this.reader = reader;
    }

    @PostMapping("/push")
    @Operation(summary = "Apply a batch of local changes; each result is applied, stale (take the server version) or invalid")
    public SyncDtos.PushResponse push(@AuthenticationPrincipal Jwt jwt, @RequestBody SyncDtos.PushRequest body) {
        var caller = Caller.of(jwt);
        var read = reader.read(body.changes());
        var result = sync.push(caller.userId(), caller.deviceId(), read.valid());
        var results = new ArrayList<>(result.results());
        results.addAll(read.rejected());
        return SyncDtos.PushResponse.of(new SyncService.PushResult(results, result.applied(), result.stale(), result.invalid() + read.rejected().size()));
    }

    @GetMapping("/pull")
    @Operation(summary = "Changes after the cursor `since` (0 = everything), in order; repeat with the returned cursor while hasMore")
    public SyncDtos.PageResponse pull(
            @AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "0") long since, @RequestParam(defaultValue = "500") int limit) {
        return SyncDtos.PageResponse.of(sync.pull(Caller.of(jwt).userId(), since, limit));
    }
}
