package io.github.roberbarras.racha.sync.adapter.persistence;

import io.github.roberbarras.racha.sync.domain.EntityType;
import io.github.roberbarras.racha.sync.domain.SyncModel;
import io.github.roberbarras.racha.sync.domain.SyncRules;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * ADR-0012 in SQL. Last writer wins inside one INSERT … ON CONFLICT … WHERE (atomic, no read-modify-write race);
 * progress keeps the highest position ever seen even when the change itself loses; history is insert-only.
 */
public final class JdbcSyncStore implements SyncModel.Store, SyncModel.Audit {
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public JdbcSyncStore(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public SyncModel.Result apply(UUID userId, SyncModel.Change change) {
        var table = Tables.ALL.get(change.entity());
        var data = change.deleted() || change.data() == null ? Map.<String, Object>of() : change.data();
        var columns = new ArrayList<>(table.columns().stream().map(Tables.Column::name).toList());
        boolean progress = change.entity() == EntityType.PROGRESS;
        boolean setting = change.entity() == EntityType.SETTING;
        if (progress) columns.add("max_position_sec");
        if (setting) columns.add("device_id");
        var placeholders = columns.stream().map(c -> ":" + c).collect(Collectors.joining(", "));
        var updates = columns.stream()
                .map(c -> c.equals("max_position_sec") ? "max_position_sec = greatest(" + table.name() + ".max_position_sec, excluded.max_position_sec)" : c + " = excluded." + c)
                .collect(Collectors.joining(", "));
        var sql = "insert into " + table.name() + " (user_id, id" + prefix(columns) + ", updated_at, deleted, seq, data) "
                + "values (:user, :id" + (columns.isEmpty() ? "" : ", " + placeholders) + ", :updatedAt, :deleted, nextval('sync_seq'), cast(:data as jsonb)) "
                + (change.entity().appendOnly()
                        ? "on conflict (user_id, id) do nothing "
                        : "on conflict (user_id, id) do update set " + (updates.isEmpty() ? "" : updates + ", ")
                                + "updated_at = excluded.updated_at, deleted = excluded.deleted, seq = excluded.seq, data = excluded.data "
                                + "where excluded.updated_at > " + table.name() + ".updated_at ")
                + "returning seq";
        var statement = jdbc.sql(sql)
                .param("user", userId).param("id", change.id()).param("updatedAt", time(change.updatedAt()))
                .param("deleted", change.deleted()).param("data", json.writeValueAsString(data));
        for (var column : table.columns()) statement = statement.param(column.name(), typed(column, data, progress));
        if (progress) statement = statement.param("max_position_sec", nonNull(SyncRules.longValue(data, "positionSec")));
        if (setting) statement = statement.param("device_id", SyncRules.settingDevice(change.id()).orElse(null));

        Optional<Long> seq = statement.query((rs, row) -> rs.getLong(1)).optional();
        if (seq.isPresent()) {
            return new SyncModel.Result(change.entity(), change.id(), SyncModel.Outcome.APPLIED, null,
                    new SyncModel.Stored(change.entity(), change.id(), change.updatedAt(), change.deleted(), seq.get(), data));
        }
        var server = get(userId, change.entity(), change.id()).orElseThrow();
        if (change.entity().appendOnly() || server.updatedAt().equals(change.updatedAt())) {
            // The same entry or the same change sent again (a retried push): nothing to do, it is there.
            return new SyncModel.Result(change.entity(), change.id(), SyncModel.Outcome.APPLIED, null, server);
        }
        if (progress && !change.deleted()) server = keepFurthestPosition(userId, change, server);
        return new SyncModel.Result(change.entity(), change.id(), SyncModel.Outcome.STALE, "the server has a newer version", server);
    }

    /** §6.3: a losing progress change still raises the highest position reached (never lose progress). */
    private SyncModel.Stored keepFurthestPosition(UUID userId, SyncModel.Change change, SyncModel.Stored server) {
        var position = SyncRules.longValue(change.data(), "positionSec");
        if (position == null) return server;
        int raised = jdbc.sql("""
                update progress set max_position_sec = :position, seq = nextval('sync_seq')
                where user_id = :user and id = :id and max_position_sec < :position""")
                .param("position", position).param("user", userId).param("id", change.id()).update();
        return raised == 0 ? server : get(userId, EntityType.PROGRESS, change.id()).orElse(server);
    }

    @Override
    public SyncModel.Page pull(UUID userId, long since, int limit) {
        var union = Arrays.stream(EntityType.values())
                .map(type -> "select '" + type.wire() + "' as entity, id, updated_at, deleted, seq, " + dataExpression(type) + " as data from "
                        + Tables.ALL.get(type).name() + " where user_id = :user and seq > :since")
                .collect(Collectors.joining(" union all "));
        var rows = jdbc.sql(union + " order by seq limit :limit")
                .param("user", userId).param("since", since).param("limit", limit + 1)
                .query(this::stored).list();
        return page(rows, since, limit);
    }

    @Override
    public SyncModel.Page list(UUID userId, EntityType type, long since, int limit, boolean includeDeleted) {
        var rows = jdbc.sql("select '" + type.wire() + "' as entity, id, updated_at, deleted, seq, " + dataExpression(type) + " as data from "
                        + Tables.ALL.get(type).name() + " where user_id = :user and seq > :since" + (includeDeleted ? "" : " and not deleted")
                        + " order by seq limit :limit")
                .param("user", userId).param("since", since).param("limit", limit + 1)
                .query(this::stored).list();
        return page(rows, since, limit);
    }

    @Override
    public Optional<SyncModel.Stored> get(UUID userId, EntityType type, String id) {
        return jdbc.sql("select '" + type.wire() + "' as entity, id, updated_at, deleted, seq, " + dataExpression(type) + " as data from "
                        + Tables.ALL.get(type).name() + " where user_id = :user and id = :id")
                .param("user", userId).param("id", id)
                .query(this::stored).optional();
    }

    @Override
    public void record(UUID userId, UUID deviceId, Instant at, int applied, int stale, int invalid) {
        jdbc.sql("""
                insert into sync_events (id, user_id, device_id, received_at, applied, stale, invalid)
                values (:id, :user, :device, :at, :applied, :stale, :invalid)""")
                .param("id", UUID.randomUUID()).param("user", userId).param("device", deviceId).param("at", time(at))
                .param("applied", applied).param("stale", stale).param("invalid", invalid)
                .update();
    }

    /** The client's data, with the server-kept highest progress position added. */
    private static String dataExpression(EntityType type) {
        return type == EntityType.PROGRESS ? "(data || jsonb_build_object('maxPositionSec', max_position_sec))::text" : "data::text";
    }

    private SyncModel.Stored stored(ResultSet rs, int row) throws SQLException {
        return new SyncModel.Stored(
                EntityType.fromWire(rs.getString("entity")).orElseThrow(), rs.getString("id"), rs.getTimestamp("updated_at").toInstant(),
                rs.getBoolean("deleted"), rs.getLong("seq"), json.readValue(rs.getString("data"), MAP));
    }

    private static SyncModel.Page page(List<SyncModel.Stored> rows, long since, int limit) {
        boolean more = rows.size() > limit;
        var changes = more ? rows.subList(0, limit) : rows;
        long cursor = changes.isEmpty() ? since : changes.getLast().seq();
        return new SyncModel.Page(List.copyOf(changes), cursor, more);
    }

    private static Object typed(Tables.Column column, Map<String, Object> data, boolean progress) {
        var value = data.get(column.field());
        return switch (column.kind()) {
            case TEXT -> value instanceof String s ? s : null;
            case BIGINT -> value instanceof Number n ? n.longValue() : (progress && column.name().equals("position_sec") ? 0L : null);
            case BOOLEAN -> value instanceof Boolean b ? b : Boolean.FALSE;
            case TIMESTAMP -> value instanceof String s ? time(Instant.parse(s)) : null;
        };
    }

    private static long nonNull(Long value) {
        return value == null ? 0 : value;
    }

    private static String prefix(List<String> columns) {
        return columns.isEmpty() ? "" : ", " + String.join(", ", columns);
    }

    private static OffsetDateTime time(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
