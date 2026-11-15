package io.github.roberbarras.racha.sync.adapter.web;

import io.github.roberbarras.racha.sync.domain.EntityType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** §7.3 `/history` (GET). */
@RestController
@RequestMapping("/history")
@Tag(name = "History")
public class HistoryController {
    private static final EntityType TYPE = EntityType.HISTORY;
    private final Collections collections;

    public HistoryController(Collections collections) {
        this.collections = collections;
    }

    @GetMapping
    @Operation(summary = "Items changed after `since` (0 = all), oldest change first")
    public SyncDtos.PageResponse list(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "0") long since, @RequestParam(defaultValue = "500") int limit) {
        return collections.list(jwt, TYPE, since, limit);
    }
}
