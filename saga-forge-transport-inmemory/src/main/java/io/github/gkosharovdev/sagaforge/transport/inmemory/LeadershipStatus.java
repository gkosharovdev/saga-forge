package io.github.gkosharovdev.sagaforge.transport.inmemory;

import io.github.gkosharovdev.sagaforge.core.spi.store.LeaderLease;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * What one relay knows about the leadership of its message store ({@code
 * specs/013-active-standby-relay} FR-022, data-model.md), from {@link
 * InMemoryTransport#leadership()}.
 *
 * <p>The canonical constructor throws {@link IllegalArgumentException} if a non-null rule below is
 * violated, or if {@code role} is {@link RelayRole#ACTIVE} without a {@code leader} held by {@code
 * relayId}.
 *
 * @param role this relay's role; non-null
 * @param relayId this relay; non-blank
 * @param leader the leadership record as last read or written, possibly expired; {@code null} if
 *     none was seen yet
 * @param observedAt when {@code leader} was last read or written; non-null
 */
public record LeadershipStatus(
    RelayRole role, String relayId, LeaderLease leader, Instant observedAt) {

  public LeadershipStatus {
    if (role == null || observedAt == null) {
      throw new IllegalArgumentException("role and observedAt must not be null");
    }
    if (relayId == null || relayId.isBlank()) {
      throw new IllegalArgumentException("relayId must not be blank");
    }
    if (role == RelayRole.ACTIVE && (leader == null || !leader.isHeldBy(relayId))) {
      throw new IllegalArgumentException("an ACTIVE relay must hold the leader lease");
    }
  }

  /**
   * @return the leadership record as last seen, if any
   */
  public Optional<LeaderLease> currentLeader() {
    return Optional.ofNullable(leader);
  }

  /**
   * Whether no relay has led the store for longer than one lease: no record was seen, or the last
   * one expired more than {@code leaseDuration} before {@code now} (health {@code DEGRADED},
   * FR-023).
   */
  public boolean isLeaderless(Instant now, Duration leaseDuration) {
    return leader == null || leader.expiresAt().plus(leaseDuration).isBefore(now);
  }
}
