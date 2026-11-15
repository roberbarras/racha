package io.github.roberbarras.racha.sync.adapter.web;

import io.github.roberbarras.racha.sync.domain.EntityType;
import io.github.roberbarras.racha.sync.domain.SyncModel;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import tools.jackson.databind.json.JsonMapper;

/** Turns request items into changes; unknown entities and oversized data become invalid results, not errors. */
public final class ChangeReader {
    public record Read(List<SyncModel.Change> valid, List<SyncModel.Result> rejected) {}

    private final JsonMapper json;
    private final int maxChangeBytes;

    public ChangeReader(JsonMapper json, int maxChangeBytes) {
        this.json = json;
        this.maxChangeBytes = maxChangeBytes;
    }

    public Read read(List<SyncDtos.ChangeDto> changes) {
        return read(changes, c -> EntityType.fromWire(c.entity()).orElse(null));
    }

    public Read items(EntityType type, List<SyncDtos.ItemDto> items) {
        var changes = items.stream().map(i -> new SyncDtos.ChangeDto(type.wire(), i.id(), i.updatedAt(), i.deleted(), i.data())).toList();
        return read(changes, c -> type);
    }

    private Read read(List<SyncDtos.ChangeDto> changes, Function<SyncDtos.ChangeDto, EntityType> typeOf) {
        if (changes == null) throw new IllegalArgumentException("changes are required");
        var valid = new ArrayList<SyncModel.Change>();
        var rejected = new ArrayList<SyncModel.Result>();
        for (var c : changes) {
            var type = typeOf.apply(c);
            if (type == null) {
                rejected.add(SyncModel.Result.invalid(null, c.id(), "unknown entity " + c.entity()));
            } else if (c.data() != null && json.writeValueAsBytes(c.data()).length > maxChangeBytes) {
                rejected.add(SyncModel.Result.invalid(type, c.id(), "data larger than " + maxChangeBytes + " bytes"));
            } else {
                valid.add(new SyncModel.Change(type, c.id(), c.updatedAt(), c.isDeleted(), c.data()));
            }
        }
        return new Read(valid, rejected);
    }
}
