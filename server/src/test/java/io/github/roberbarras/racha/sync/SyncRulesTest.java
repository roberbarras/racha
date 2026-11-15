package io.github.roberbarras.racha.sync;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.roberbarras.racha.sync.domain.EntityType;
import io.github.roberbarras.racha.sync.domain.SyncModel.Change;
import io.github.roberbarras.racha.sync.domain.SyncRules;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SyncRulesTest {
    private static final Instant NOW = Instant.parse("2026-11-15T10:00:00Z");

    @Test
    void clocksAheadAreClamped() {
        assertThat(SyncRules.clamp(NOW.plus(Duration.ofMinutes(4)), NOW)).isEqualTo(NOW.plus(Duration.ofMinutes(4)));
        assertThat(SyncRules.clamp(NOW.plus(Duration.ofDays(3)), NOW)).isEqualTo(NOW);
        assertThat(SyncRules.clamp(NOW.minus(Duration.ofDays(3)), NOW)).isEqualTo(NOW.minus(Duration.ofDays(3)));
    }

    @Test
    void validation() {
        assertThat(SyncRules.problem(new Change(EntityType.PROGRESS, "e", NOW, false, Map.of("positionSec", 10)))).isEmpty();
        assertThat(SyncRules.problem(new Change(EntityType.PROGRESS, "e", NOW, false, Map.of("positionSec", -1)))).isPresent();
        assertThat(SyncRules.problem(new Change(EntityType.PROGRESS, "e", NOW, false, Map.of("positionSec", 1, "completed", "yes")))).isPresent();
        assertThat(SyncRules.problem(new Change(EntityType.PROGRESS, "e", NOW, true, null))).as("tombstones need no data").isEmpty();
        assertThat(SyncRules.problem(new Change(EntityType.CATEGORY, "", NOW, false, Map.of()))).isPresent();
        assertThat(SyncRules.problem(new Change(EntityType.CATEGORY, "c", null, false, Map.of()))).isPresent();
        assertThat(SyncRules.problem(new Change(EntityType.SETTING, "user:theme", NOW, false, Map.of("value", "dark")))).isEmpty();
        assertThat(SyncRules.problem(new Change(EntityType.SETTING, "device:" + UUID.randomUUID() + ":zoom", NOW, false, Map.of("value", "fit")))).isEmpty();
        assertThat(SyncRules.problem(new Change(EntityType.SETTING, "theme", NOW, false, Map.of("value", "dark")))).isPresent();
        assertThat(SyncRules.problem(new Change(EntityType.HISTORY, "h", NOW, false, Map.of("elementId", "e", "watchedAt", "2026-11-15T09:00:00Z")))).isEmpty();
    }
}
