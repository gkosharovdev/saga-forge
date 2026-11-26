package io.github.gkosharovdev.sagaforge.transport.inmemory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gkosharovdev.sagaforge.core.spi.store.LeaderLease;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link LeadershipStatus} rules ({@code specs/013-active-standby-relay} data-model.md). */
class LeadershipStatusTest {

  private static final Instant T0 = Instant.parse("2026-11-25T10:00:00Z");
  private static final Duration LEASE = Duration.ofSeconds(30);
  private static final LeaderLease A = new LeaderLease("A", "host-A", 1, T0, T0.plus(LEASE));

  @Test
  @DisplayName("an ACTIVE relay holds the lease; a STANDBY one may see another relay's lease")
  void roles() {
    assertThat(new LeadershipStatus(RelayRole.ACTIVE, "A", A, T0).currentLeader()).contains(A);
    assertThat(new LeadershipStatus(RelayRole.STANDBY, "B", A, T0).currentLeader()).contains(A);
    assertThat(new LeadershipStatus(RelayRole.STANDBY, "B", null, T0).currentLeader()).isEmpty();
  }

  @Test
  @DisplayName(
      "ACTIVE without a lease held by the relay, a blank relay id or null parts are rejected")
  void rejected() {
    assertThatThrownBy(() -> new LeadershipStatus(RelayRole.ACTIVE, "B", A, T0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ACTIVE");
    assertThatThrownBy(() -> new LeadershipStatus(RelayRole.ACTIVE, "A", null, T0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new LeadershipStatus(RelayRole.STANDBY, " ", null, T0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new LeadershipStatus(null, "A", null, T0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new LeadershipStatus(RelayRole.STANDBY, "A", null, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("leaderless when no lease was seen or the last one expired more than a lease ago")
  void leaderless() {
    LeadershipStatus seen = new LeadershipStatus(RelayRole.STANDBY, "B", A, T0);

    assertThat(new LeadershipStatus(RelayRole.STANDBY, "B", null, T0).isLeaderless(T0, LEASE))
        .isTrue();
    assertThat(seen.isLeaderless(A.expiresAt().plus(LEASE), LEASE)).isFalse();
    assertThat(seen.isLeaderless(A.expiresAt().plus(LEASE).plusMillis(1), LEASE)).isTrue();
  }
}
