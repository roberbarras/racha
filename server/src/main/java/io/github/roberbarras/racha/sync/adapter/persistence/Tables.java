package io.github.roberbarras.racha.sync.adapter.persistence;

import io.github.roberbarras.racha.sync.domain.EntityType;
import java.util.List;
import java.util.Map;

/** Where each entity lives and which of its fields are copied into typed columns (V3__synced_entities.sql, V8__summaries.sql). */
final class Tables {
    enum Kind { TEXT, BIGINT, BOOLEAN, TIMESTAMP }

    record Column(String name, String field, Kind kind) {}

    record Table(String name, List<Column> columns) {}

    static final Map<EntityType, Table> ALL = Map.ofEntries(
            Map.entry(EntityType.CATEGORY, new Table("categories", List.of())),
            Map.entry(EntityType.CATEGORY_ROOT, new Table("category_roots", List.of(text("category_id", "categoryId")))),
            Map.entry(EntityType.LIBRARY_ITEM, new Table("library_items", List.of(text("category_id", "categoryId")))),
            Map.entry(EntityType.SECTION, new Table("sections", List.of(text("item_id", "itemId")))),
            Map.entry(EntityType.ELEMENT, new Table("playable_elements", List.of(text("item_id", "itemId"), bigint("duration_sec", "durationSec")))),
            Map.entry(EntityType.SUBTITLE, new Table("subtitles", List.of(text("element_id", "elementId")))),
            Map.entry(EntityType.PROGRESS, new Table("progress", List.of(
                    bigint("position_sec", "positionSec"), bigint("duration_sec", "durationSec"), new Column("completed", "completed", Kind.BOOLEAN)))),
            Map.entry(EntityType.BOOKMARK, new Table("bookmarks", List.of(text("element_id", "elementId")))),
            Map.entry(EntityType.HISTORY, new Table("history", List.of(
                    text("element_id", "elementId"), new Column("watched_at", "watchedAt", Kind.TIMESTAMP), bigint("position_sec", "positionSec")))),
            Map.entry(EntityType.FAVORITE, new Table("favorites", List.of())),
            Map.entry(EntityType.SUMMARY, new Table("summaries", List.of(text("target", "target")))),
            Map.entry(EntityType.SETTING, new Table("app_settings", List.of())));

    private Tables() {}

    private static Column text(String name, String field) {
        return new Column(name, field, Kind.TEXT);
    }

    private static Column bigint(String name, String field) {
        return new Column(name, field, Kind.BIGINT);
    }
}
