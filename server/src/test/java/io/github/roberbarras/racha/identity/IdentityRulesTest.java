package io.github.roberbarras.racha.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.roberbarras.racha.identity.domain.Email;
import io.github.roberbarras.racha.identity.domain.PasswordPolicy;
import io.github.roberbarras.racha.identity.domain.RefreshSession;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdentityRulesTest {
    @Test
    void emailsAreNormalised() {
        assertThat(Email.of("  Ana@Example.COM ").value()).isEqualTo("ana@example.com");
        assertThatThrownBy(() -> Email.of("ana@example")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Email.of("a b@example.com")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void passwordsNeedLengthNotSymbols() {
        assertThat(PasswordPolicy.accepts("correct horse battery staple")).isTrue();
        assertThat(PasswordPolicy.accepts("short")).isFalse();
        assertThat(PasswordPolicy.accepts("          ")).isFalse();
        assertThat(PasswordPolicy.accepts("x".repeat(129))).isFalse();
    }

    @Test
    void refreshSessionStates() {
        var now = Instant.parse("2026-11-15T10:00:00Z");
        var session = new RefreshSession(UUID.randomUUID(), UUID.randomUUID(), null, UUID.randomUUID(), new byte[32], now, now.plusSeconds(60), null, null);
        assertThat(session.stateAt(now)).isEqualTo(RefreshSession.State.ACTIVE);
        assertThat(session.stateAt(now.plusSeconds(60))).isEqualTo(RefreshSession.State.EXPIRED);
        var used = new RefreshSession(session.id(), session.userId(), null, session.familyId(), session.tokenHash(), now, session.expiresAt(), now, null);
        assertThat(used.stateAt(now)).isEqualTo(RefreshSession.State.USED);
    }
}
