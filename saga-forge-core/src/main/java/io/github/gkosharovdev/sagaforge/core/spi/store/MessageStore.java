package io.github.gkosharovdev.sagaforge.core.spi.store;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import io.github.gkosharovdev.sagaforge.core.spi.LocalTransaction;

/**
 * The durable, ordered log behind a transactional outbox ({@code specs/007-in-memory-transport} FR-001 to FR-023,
 * data-model.md). Storage-agnostic: implemented by storage modules ({@code PostgresMessageStore}) and decorated in
 * Spring Modulith interop mode; used by transports.
 *
 * <h3>Transactions</h3>
 * <p>Methods taking a {@link LocalTransaction} run in it and use its resources (for JDBC,
 * {@code tx.resource(Connection.class)}). Every other method runs in a short transaction of its own.</p>
 *
 * <h3>Leasable</h3>
 * <p>A message is leasable when it is {@link MessageStatus#UNCONSUMED}, not leased or its lease expired, due
 * ({@code nextAttemptAt <= now}), and no {@code UNCONSUMED} message with the same non-null ordering key has a lower
 * position. {@link MessageStatus#SET_ASIDE} messages do not block their key.</p>
 *
 * <h3>Leaseholder writes</h3>
 * <p>{@code markConsumed}, {@code recordFailure} and {@code setAside} apply only if {@code consumerId} still holds the
 * message's lease; otherwise they write nothing and throw {@link LeaseLostException}.</p>
 */
public interface MessageStore {

    /**
     * Appends {@code entries} in {@code tx}, assigning increasing positions in list order. They become leasable when
     * {@code tx} commits and vanish if it rolls back.
     *
     * @throws DuplicateMessageException if a {@code messageId} is already in the store
     */
    void append(LocalTransaction tx, List<NewMessage> entries);

    /**
     * Reserves up to {@code limit} leasable messages, in position order, for {@code consumerId} until
     * {@code now + leaseDuration}, and counts one delivery attempt for each.
     */
    LeasedMessages lease(String consumerId, Instant now, Duration leaseDuration, int limit);

    /**
     * Extends to {@code until} the leases {@code consumerId} still holds among {@code messageIds}.
     *
     * @return the ids whose lease {@code consumerId} no longer holds
     */
    Set<String> renew(String consumerId, Collection<String> messageIds, Instant until);

    /** Marks the message consumed, in a transaction of its own; releases the lease. */
    void markConsumed(String consumerId, String messageId, Instant at);

    /** Marks the message consumed in {@code tx}, for example inside a outbox command's unit of work. */
    void markConsumed(LocalTransaction tx, String consumerId, String messageId, Instant at);

    /** Records a failed attempt, releases the lease and makes the message due again at {@code nextAttemptAt}. */
    void recordFailure(String consumerId, String messageId, DeliveryFailure failure, Instant nextAttemptAt);

    /** Sets the message aside with {@code failure} and {@code reason}; releases the lease. */
    void setAside(String consumerId, String messageId, DeliveryFailure failure, String reason);

    /** Releases every lease {@code consumerId} holds, leaving the messages unconsumed (shutdown). */
    void release(String consumerId);

    /** @return the messages matching {@code query}, in position order */
    List<StoredMessage> query(MessageQuery query);

    /**
     * Makes a {@link MessageStatus#SET_ASIDE} message unconsumed again with no attempts, due at {@code now}, keeping
     * its last failure (FR-023).
     *
     * @return {@code false} if no such message is set aside
     */
    boolean redeliver(String messageId, Instant now);

    /**
     * Deletes at most {@code batchSize} {@link MessageStatus#CONSUMED} messages consumed before {@code cutoff}.
     *
     * @return the number deleted
     */
    int purgeConsumed(Instant cutoff, int batchSize);

    /** Inserts or refreshes a consumer's heartbeat. */
    void heartbeat(ConsumerHeartbeat heartbeat);

    /** @return the heartbeats last seen at or after {@code since} */
    List<ConsumerHeartbeat> liveConsumers(Instant since);
}
