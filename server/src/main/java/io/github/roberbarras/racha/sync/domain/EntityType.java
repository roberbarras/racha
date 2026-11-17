package io.github.roberbarras.racha.sync.domain;

import java.util.Arrays;
import java.util.Optional;

/** Synchronised entities (§6.2, ADR-0012) with their name on the wire. */
public enum EntityType {
    CATEGORY("category"),
    CATEGORY_ROOT("categoryRoot"),
    LIBRARY_ITEM("libraryItem"),
    SECTION("section"),
    ELEMENT("element"),
    SUBTITLE("subtitle"),
    PROGRESS("progress"),
    BOOKMARK("bookmark"),
    HISTORY("history"),
    FAVORITE("favorite"),
    SUMMARY("summary"),
    SETTING("setting");

    private final String wire;

    EntityType(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    /** History entries never change once written: a repeated id is the same entry. */
    public boolean appendOnly() {
        return this == HISTORY;
    }

    /** A losing settings change goes back to the client so it can ask the user (§6.3). */
    public boolean asksOnConflict() {
        return this == SETTING;
    }

    public static Optional<EntityType> fromWire(String wire) {
        return Arrays.stream(values()).filter(t -> t.wire.equals(wire)).findFirst();
    }
}
