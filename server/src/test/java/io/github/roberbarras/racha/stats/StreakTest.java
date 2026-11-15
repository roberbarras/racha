package io.github.roberbarras.racha.stats;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.roberbarras.racha.stats.domain.Streak;
import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StreakTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 15);

    @Test
    void countsBackFromTodayOrYesterday() {
        assertThat(Streak.days(Set.of(TODAY, TODAY.minusDays(1), TODAY.minusDays(2)), TODAY)).isEqualTo(3);
        assertThat(Streak.days(Set.of(TODAY.minusDays(1), TODAY.minusDays(2)), TODAY)).as("not broken until tomorrow").isEqualTo(2);
        assertThat(Streak.days(Set.of(TODAY.minusDays(2)), TODAY)).isZero();
        assertThat(Streak.days(Set.of(), TODAY)).isZero();
    }
}
