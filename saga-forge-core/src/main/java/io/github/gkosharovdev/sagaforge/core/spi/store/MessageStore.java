package io.github.gkosharovdev.sagaforge.core.spi.store;

import io.github.gkosharovdev.sagaforge.core.spi.LocalTransaction;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * The durable, ordered log behind a transactional outbox ({@code specs/007-in-memory-transport}
 * FR-001 to FR-023, data-model.md). Storage-agnostic: implemented by storage modules ({@code
 * PostgresMessageStore}) and decorated in Spring Modulith interop mode; used by transports.
 *
 * <h2>Transactions</h2>
 *
 * <p>Methods taking a {@link LocalTransaction} run in it and use its resources (for JDBC, {@code
 * tx.resource(Connection.class)}). Every other method runs in a short transaction of its own.
 *
 * <h2>Leasable</h2>
 *
 * <p>A message is leasable when it is {@link MessageStatus#UNCONSUMED}, not leased or its lease
 * expired, due ({@code nextAttemptAt <= now}), and no {@code UNCONSUMED} message with the same
 * non-null ordering key has a lower position. {@link MessageStatus#SET_ASIDE} messages do not block
 * their key.
 *
 * <h2>Leadership</h2>
 *
 * <p>Several application instances may share one store, but only one relay delivers: the one
 * holding the store's {@link LeaderLease} ({@link #leaderElection()}). {@link #lease} is fenced by
 * that lease's token in the same transaction ({@code specs/013-active-standby-relay} FR-008). Every
 * other method may be called by any instance, leader or not.
 *
 * <h2>Leaseholder writes</h2>
 *
 * <p>{@code markConsumed}, {@code recordFailure} and {@code setAside} apply only if {@code
 * consumerId} still holds the message's lease; otherwise they write nothing and throw {@link
 * LeaseLostException}.
 */
public interface MessageStore {

  /**
   * Appends {@code entries} in {@code tx}, assigning increasing positions in list order. They
   * become leasable when {@code tx} commits and vanish if it rolls back.
   *
   * @throws DuplicateMessageException if a {@code messageId} is already in the store
   */
  void append(LocalTransaction tx, List<NewMessage> entries);

  /**
   * The leader election of this store, whose current lease fences {@link #lease}.
   *
   * @return the election; the same instance on every call
   */
  LeaderElection leaderElection();

  /**
   * In one transaction: checks that {@code leader} is this store's current leadership and has not
   * expired at {@code now}, then reserves up to {@code limit} leasable messages, in position order,
   * for {@code leader.holderId()} until {@code now + leaseDuration}, and counts one delivery
   * attempt for each. A takeover cannot interleave with a lease: it waits for it, or the lease sees
   * it.
   *
   * @throws LeadershipLostException if {@code leader} is not current at {@code now}; nothing was
   *     leased
   */
  LeasedMessages lease(LeaderLease leader, Instant now, Duration leaseDuration, int limit);

  /**
   * Extends to {@code until} the leases {@code consumerId} still holds among {@code messageIds}.
   *
   * @return the ids whose lease {@code consumerId} no longer holds
   */
  Set<String> renew(String consumerId, Collection<String> messageIds, Instant until);

  /** Marks the message consumed, in a transaction of its own; releases the lease. */
  void markConsumed(String consumerId, String messageId, Instant at);

  /**
   * Marks the message consumed in {@code tx}, for example inside a outbox command's unit of work.
   */
  void markConsumed(LocalTransaction tx, String consumerId, String messageId, Instant at);

  /**
   * Records a failed attempt, releases the lease and makes the message due again at {@code
   * nextAttemptAt}.
   */
  void recordFailure(
      String consumerId, String messageId, DeliveryFailure failure, Instant nextAttemptAt);

  /** Sets the message aside with {@code failure} and {@code reason}; releases the lease. */
  void setAside(String consumerId, String messageId, DeliveryFailure failure, String reason);

  /**
   * Sets the message aside in {@code tx}, like {@link #setAside(String, String, DeliveryFailure,
   * String)}, so the set-aside commits or rolls back together with the work that runs in {@code
   * tx}, for example the engine's reaction to a failed outbox command ({@code
   * specs/012-outbox-command-failure-feedback}). If {@code tx} rolls back, the message stays leased
   * by {@code consumerId}.
   *
   * <p>This default throws {@link UnsupportedOperationException}, so a store written before the
   * method existed still compiles.
   */
  default void setAside(
      LocalTransaction tx,
      String consumerId,
      String messageId,
      DeliveryFailure failure,
      String reason) {
    throw new UnsupportedOperationException(
        getClass().getName() + " cannot set a message aside in a transaction");
  }

  /** Releases every lease {@code consumerId} holds, leaving the messages unconsumed (shutdown). */
  void release(String consumerId);

  /**
   * @return the messages matching {@code query}, in position order
   */
  List<StoredMessage> query(MessageQuery query);

  /**
   * Makes a {@link MessageStatus#SET_ASIDE} message unconsumed again with no attempts, due at
   * {@code now}, keeping its last failure (FR-023).
   *
   * @return {@code false} if no such message is set aside
   */
  boolean redeliver(String messageId, Instant now);

  /**
   * Deletes at most {@code batchSize} {@link MessageStatus#CONSUMED} messages consumed before
   * {@code cutoff}.
   *
   * @return the number deleted
   */
  int purgeConsumed(Instant cutoff, int batchSize);

  /**
   * Whether this store keeps a message's {@link NewMessage#notBefore()} and implements {@link
   * #cancel(LocalTransaction, Collection, Instant)} ({@code specs/017-saga-deadlines} FR-001 to
   * FR-005). A message with a {@code notBefore} is held back by the leasable rule above: its {@code
   * nextAttemptAt} starts at {@code notBefore}.
   *
   * @return {@code false} unless the store overrides it
   */
  default boolean supportsScheduling() {
    return false;
  }

  /**
   * Cancels, in {@code tx}, each listed message that is {@link MessageStatus#UNCONSUMED} and not
   * held by an unexpired lease at {@code at}: it becomes {@link MessageStatus#CANCELLED} with
   * {@code consumedAt = at}, is never leased again and does not block its ordering key. A leased,
   * consumed, set-aside, cancelled or unknown message is left unchanged. If {@code tx} rolls back,
   * nothing is cancelled.
   *
   * <p>This default throws {@link UnsupportedOperationException}.
   *
   * @param tx the transaction to write in
   * @param messageIds the messages to cancel
   * @param at the cancellation time, also the time leases are judged against
   * @return the ids of the messages this call cancelled
   */
  default Set<String> cancel(LocalTransaction tx, Collection<String> messageIds, Instant at) {
    throw new UnsupportedOperationException(
        getClass().getName() + " does not support cancelling messages");
  }
}
