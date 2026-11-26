package io.github.gkosharovdev.sagaforge.core.spi.store;

import java.time.Instant;

/**
 * The leadership record of one message store, as last written or read ({@code
 * specs/013-active-standby-relay} data-model.md). At most one relay holds an unexpired lease; only
 * that relay may lease messages, and {@link MessageStore#lease} checks the {@link #fencingToken()}
 * in the same transaction, so a relay that lost its leadership cannot lease again with a stale
 * token. The shape follows Kubernetes' {@code coordination.k8s.io} Lease; the token is a fencing
 * token in the usual sense of distributed locking.
 *
 * <p>The canonical constructor throws {@link IllegalArgumentException} if a rule below is violated.
 *
 * @param holderId the relay holding the lease; non-blank
 * @param holderHost the holder's host name, informational; non-blank
 * @param fencingToken at least 1; strictly increasing per store across acquisitions, unchanged by
 *     renewal
 * @param acquiredAt when the acquisition that issued the token happened; non-null
 * @param expiresAt when the lease ends unless renewed; non-null, not before {@code acquiredAt}
 */
public record LeaderLease(
    String holderId, String holderHost, long fencingToken, Instant acquiredAt, Instant expiresAt) {

  public LeaderLease {
    Checks.nonBlank(holderId, "holderId");
    Checks.nonBlank(holderHost, "holderHost");
    if (fencingToken < 1) {
      throw new IllegalArgumentException("fencingToken must be at least 1, but is " + fencingToken);
    }
    Checks.nonNull(acquiredAt, "acquiredAt");
    Checks.nonNull(expiresAt, "expiresAt");
    if (expiresAt.isBefore(acquiredAt)) {
      throw new IllegalArgumentException(
          "expiresAt " + expiresAt + " must not be before acquiredAt " + acquiredAt);
    }
  }

  /**
   * @return whether the lease has ended at {@code now} (an expiry exactly at {@code now} counts)
   */
  public boolean isExpiredAt(Instant now) {
    return !now.isBefore(expiresAt);
  }

  /**
   * @return whether {@code relayId} holds this lease
   */
  public boolean isHeldBy(String relayId) {
    return holderId.equals(relayId);
  }

  /**
   * @return this lease, renewed until {@code until}; same holder and token
   */
  public LeaderLease renewedUntil(Instant until) {
    return new LeaderLease(holderId, holderHost, fencingToken, acquiredAt, until);
  }
}
