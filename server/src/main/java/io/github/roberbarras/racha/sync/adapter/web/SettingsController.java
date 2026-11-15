package io.github.roberbarras.racha.sync.adapter.web;

import io.github.roberbarras.racha.shared.adapter.web.Caller;
import io.github.roberbarras.racha.sync.application.SyncService;
import io.github.roberbarras.racha.sync.domain.EntityType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * §7.3 `/settings` (GET/PUT), per user (`user:<key>`) and per device (`device:<deviceId>:<key>`). A PUT that loses to
 * a newer server value comes back as `stale` with that value, so the app can ask the user (§6.3).
 */
@RestController
@RequestMapping("/settings")
@Tag(name = "Settings")
public class SettingsController {
    private final SyncService sync;
    private final Collections collections;

    public SettingsController(SyncService sync, Collections collections) {
        this.sync = sync;
        this.collections = collections;
    }

    @GetMapping
    @Operation(summary = "The user's settings and this device's own settings")
    public List<SyncDtos.RecordDto> get(@AuthenticationPrincipal Jwt jwt) {
        var caller = Caller.of(jwt);
        var mine = caller.deviceId() == null ? "" : "device:" + caller.deviceId() + ":";
        return sync.list(caller.userId(), EntityType.SETTING, 0, SyncService.MAX_PAGE).changes().stream()
                .filter(s -> s.id().startsWith("user:") || (!mine.isEmpty() && s.id().startsWith(mine)))
                .map(SyncDtos.RecordDto::of)
                .toList();
    }

    @PutMapping
    @Operation(summary = "Save settings; each result is applied, stale (newer value on the server: ask the user) or invalid")
    public SyncDtos.PushResponse put(@AuthenticationPrincipal Jwt jwt, @RequestBody List<SyncDtos.ItemDto> items) {
        return collections.upsert(jwt, EntityType.SETTING, items);
    }
}
