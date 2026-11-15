package io.github.roberbarras.racha.sync.adapter.web;

import io.github.roberbarras.racha.sync.domain.EntityType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** §7.3 `/categories` (GET/POST/PUT/DELETE). */
@RestController
@RequestMapping("/categories")
@Tag(name = "Categories")
public class CategoriesController {
    private static final EntityType TYPE = EntityType.CATEGORY;
    private final Collections collections;

    public CategoriesController(Collections collections) {
        this.collections = collections;
    }

    @GetMapping
    @Operation(summary = "Items changed after `since` (0 = all), oldest change first")
    public SyncDtos.PageResponse list(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "0") long since, @RequestParam(defaultValue = "500") int limit) {
        return collections.list(jwt, TYPE, since, limit);
    }
    @GetMapping("/{id}")
    @Operation(summary = "One item")
    public ResponseEntity<?> get(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return collections.get(jwt, TYPE, id);
    }
    @PostMapping
    @Operation(summary = "Create or update a batch (last writer wins by updatedAt)")
    public SyncDtos.PushResponse post(@AuthenticationPrincipal Jwt jwt, @RequestBody List<SyncDtos.ItemDto> items) {
        return collections.upsert(jwt, TYPE, items);
    }
    @PutMapping("/{id}")
    @Operation(summary = "Create or update one item: 200 applied, 409 the server has a newer version (in the body)")
    public ResponseEntity<SyncDtos.ResultDto> put(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @RequestBody SyncDtos.ItemDto item) {
        return collections.put(jwt, TYPE, id, item);
    }
    @DeleteMapping("/{id}")
    @Operation(summary = "Delete (kept as a tombstone so the other devices learn about it)")
    public ResponseEntity<SyncDtos.ResultDto> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return collections.delete(jwt, TYPE, id);
    }
}
