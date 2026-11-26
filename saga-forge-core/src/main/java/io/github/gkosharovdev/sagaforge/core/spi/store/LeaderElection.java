package io.github.gkosharovdev.sagaforge.core.spi.store;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Leader election over a message store: at most one relay holds an unexpired {@link LeaderLease} at
 * any time ({@code specs/013-active-standby-relay} FR-001 to FR-003, contracts/
 * leader-election-spi.md). Reached through {@link MessageStore#leaderElection()}, because the
 * store's {@link MessageStore#lease} is fenced by the current lease in the same transaction.
 *
 * <p>Times come from the caller's clock, like every other {@link MessageStore} time. Each method
 * runs in a short transaction of its own and is safe to call concurrently from several processes.
 */
public interface LeaderElection {

  /**
   * Becomes leader if no leadership was ever acquired or the current one has expired at {@code
   * now}. The new lease has a fencing token greater than every earlier one of this store, {@code
   * acquiredAt = now} and {@code expiresAt = now + leaseDuration}.
   *
   * @return the new lease, or empty if an unexpired leadership exists (also one held by {@code
   *     candidateId})
   */
  Optional<LeaderLease> tryAcquire(
      String candidateId, String host, Instant now, Duration leaseDuration);

  /**
   * Extends {@code lease} to {@code now + leaseDuration} if it is still the current leadership
   * (same fencing token) and has not expired at {@code now}. An expired lease is not renewed: it is
   * acquired again, so the token grows.
   *
   * @return the extended lease, with the same token, or empty if the leadership was lost
   */
  Optional<LeaderLease> renew(LeaderLease lease, Instant now, Duration leaseDuration);

  /**
   * Ends {@code lease} at {@code now} if it is still the current leadership, so another relay can
   * acquire at once; otherwise does nothing.
   */
  void release(LeaderLease lease, Instant now);

  /**
   * @return the leadership record as last written, expired or not; empty if none was ever acquired
   */
  Optional<LeaderLease> current();
}
