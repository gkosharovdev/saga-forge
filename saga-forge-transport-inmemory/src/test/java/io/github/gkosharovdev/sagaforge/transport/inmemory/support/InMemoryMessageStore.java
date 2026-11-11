package io.github.gkosharovdev.sagaforge.transport.inmemory.support;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import io.github.gkosharovdev.sagaforge.core.spi.LocalTransaction;
import io.github.gkosharovdev.sagaforge.core.spi.store.LeasedMessages;
import io.github.gkosharovdev.sagaforge.core.spi.store.ConsumerHeartbeat;
import io.github.gkosharovdev.sagaforge.core.spi.store.DeliveryFailure;
import io.github.gkosharovdev.sagaforge.core.spi.store.DuplicateMessageException;
import io.github.gkosharovdev.sagaforge.core.spi.store.LeaseLostException;
import io.github.gkosharovdev.sagaforge.core.spi.store.NewMessage;
import io.github.gkosharovdev.sagaforge.core.spi.store.StoredMessage;
import io.github.gkosharovdev.sagaforge.core.spi.store.MessageStore;
import io.github.gkosharovdev.sagaforge.core.spi.store.MessageQuery;
import io.github.gkosharovdev.sagaforge.core.spi.store.MessageStatus;
import io.github.gkosharovdev.sagaforge.core.spi.store.UnreadableMessageException;
import io.github.gkosharovdev.sagaforge.runtime.support.InMemoryTransactionBoundary.Scope;

/**
 * A thread-safe {@link MessageStore} double for transport tests ({@code specs/007-in-memory-transport} tasks.md T019).
 *
 * <p><strong>Test-only scaffolding.</strong> This class lives in {@code src/test} and must never move to
 * {@code src/main} (Constitution Principle IV).</p>
 *
 * <ul>
 *   <li>Entries appended in a transaction of {@code InMemoryTransactionBoundary} become visible when it commits and
 *       vanish when it rolls back; positions are assigned at append, as a database sequence would.</li>
 *   <li>{@code markConsumed(tx, ...)} checks the lease at the call and applies on commit.</li>
 *   <li>Every lease invariant of data-model.md holds (head of key, lease expiry, due time).</li>
 *   <li>Payloads are kept as the objects appended. {@link #markUnreadable(String)} makes a message's payload
 *       undecodable; {@link #failNext(Operation, RuntimeException)} injects one failure into an operation;
 *       {@link #pauseLeasing(boolean)} makes leases return nothing, like a stopped process.</li>
 * </ul>
 */
public final class InMemoryMessageStore implements MessageStore {

    /** Operations that {@link #failNext} can make throw. */
    public enum Operation { APPEND, LEASE, MARK_CONSUMED, RECORD_FAILURE, SET_ASIDE }

    private final AtomicLong positions = new AtomicLong();
    private final Map<String, StoredMessage> messages = new LinkedHashMap<>();
    private final Set<String> unreadable = ConcurrentHashMap.newKeySet();
    private final Map<Operation, RuntimeException> failures = new EnumMap<>(Operation.class);
    private final Map<String, ConsumerHeartbeat> heartbeats = new ConcurrentHashMap<>();
    private volatile boolean leasingPaused;
    private final Map<String, RuntimeException> appendFailures = new ConcurrentHashMap<>();

    @Override
    public void append(LocalTransaction tx, List<NewMessage> entries) {
        injected(Operation.APPEND);
        for (NewMessage entry : entries) {
            RuntimeException failure = appendFailures.remove(entry.payloadType());
            if (failure != null) {
                throw failure;
            }
        }
        List<StoredMessage> pending = new ArrayList<>();
        synchronized (this) {
            Set<String> ids = new HashSet<>();
            for (NewMessage entry : entries) {
                if (messages.containsKey(entry.messageId()) || !ids.add(entry.messageId())) {
                    throw new DuplicateMessageException(entry.messageId());
                }
                pending.add(StoredMessage.appended(positions.incrementAndGet(), entry));
            }
        }
        scope(tx).onCommit(() -> {
            synchronized (this) {
                for (StoredMessage message : pending) {
                    if (messages.putIfAbsent(message.messageId(), message) != null) {
                        throw new DuplicateMessageException(message.messageId());
                    }
                }
                sortByPosition();
            }
        });
    }

    @Override
    public LeasedMessages lease(String consumerId, Instant now, Duration leaseDuration, int limit) {
        injected(Operation.LEASE);
        if (leasingPaused) {
            return new LeasedMessages(List.of(), List.of());
        }
        List<StoredMessage> readable = new ArrayList<>();
        List<UnreadableMessageException> unreadableLeases = new ArrayList<>();
        synchronized (this) {
            Set<String> blockedKeys = new HashSet<>();
            for (StoredMessage message : messages.values()) {
                if (readable.size() + unreadableLeases.size() >= limit) {
                    break;
                }
                if (message.status() != MessageStatus.UNCONSUMED) {
                    continue;
                }
                String key = message.orderingKey();
                boolean headOfKey = key == null || blockedKeys.add(key);
                boolean heldByOther = message.leasedUntil() != null && message.leasedUntil().isAfter(now);
                boolean due = !message.nextAttemptAt().isAfter(now);
                if (!headOfKey || heldByOther || !due) {
                    continue;
                }
                StoredMessage leased = with(message, MessageStatus.UNCONSUMED, message.attempts() + 1,
                        message.nextAttemptAt(), message.lastFailure(), consumerId, now.plus(leaseDuration), null, null);
                messages.put(leased.messageId(), leased);
                if (unreadable.contains(leased.messageId())) {
                    unreadableLeases.add(new UnreadableMessageException(leased,
                            new ClassNotFoundException(leased.payloadType())));
                } else {
                    readable.add(leased);
                }
            }
        }
        return new LeasedMessages(readable, unreadableLeases);
    }

    @Override
    public synchronized Set<String> renew(String consumerId, Collection<String> messageIds, Instant until) {
        Set<String> lost = new HashSet<>();
        for (String id : messageIds) {
            StoredMessage message = messages.get(id);
            if (message == null || !consumerId.equals(message.leasedBy())
                    || message.status() != MessageStatus.UNCONSUMED) {
                lost.add(id);
                continue;
            }
            messages.put(id, with(message, message.status(), message.attempts(), message.nextAttemptAt(),
                    message.lastFailure(), consumerId, until, null, null));
        }
        return lost;
    }

    @Override
    public void markConsumed(String consumerId, String messageId, Instant at) {
        injected(Operation.MARK_CONSUMED);
        synchronized (this) {
            consume(holder(consumerId, messageId), at);
        }
    }

    @Override
    public void markConsumed(LocalTransaction tx, String consumerId, String messageId, Instant at) {
        injected(Operation.MARK_CONSUMED);
        synchronized (this) {
            holder(consumerId, messageId);
        }
        scope(tx).onCommit(() -> {
            synchronized (this) {
                consume(holder(consumerId, messageId), at);
            }
        });
    }

    @Override
    public void recordFailure(String consumerId, String messageId, DeliveryFailure failure, Instant nextAttemptAt) {
        injected(Operation.RECORD_FAILURE);
        synchronized (this) {
            StoredMessage message = holder(consumerId, messageId);
            messages.put(messageId, with(message, MessageStatus.UNCONSUMED, message.attempts(), nextAttemptAt,
                    failure, null, null, null, null));
        }
    }

    @Override
    public void setAside(String consumerId, String messageId, DeliveryFailure failure, String reason) {
        injected(Operation.SET_ASIDE);
        synchronized (this) {
            StoredMessage message = holder(consumerId, messageId);
            messages.put(messageId, with(message, MessageStatus.SET_ASIDE, message.attempts(),
                    message.nextAttemptAt(), failure, null, null, null, reason));
        }
    }

    @Override
    public synchronized void release(String consumerId) {
        for (StoredMessage message : List.copyOf(messages.values())) {
            if (consumerId.equals(message.leasedBy())) {
                messages.put(message.messageId(), with(message, message.status(), message.attempts(),
                        message.nextAttemptAt(), message.lastFailure(), null, null, null, null));
            }
        }
    }

    @Override
    public synchronized List<StoredMessage> query(MessageQuery query) {
        return messages.values().stream().filter(query::matches)
                .filter(message -> !query.includePayload() || !unreadable.contains(message.messageId()))
                .map(message -> query.includePayload() ? message : message.withoutPayload())
                .limit(query.limit()).toList();
    }

    @Override
    public synchronized boolean redeliver(String messageId, Instant now) {
        StoredMessage message = messages.get(messageId);
        if (message == null || message.status() != MessageStatus.SET_ASIDE) {
            return false;
        }
        messages.put(messageId, with(message, MessageStatus.UNCONSUMED, 0, now, message.lastFailure(), null, null,
                null, null));
        return true;
    }

    @Override
    public synchronized int purgeConsumed(Instant cutoff, int batchSize) {
        List<String> purged = messages.values().stream()
                .filter(message -> message.status() == MessageStatus.CONSUMED
                        && message.consumedAt().isBefore(cutoff))
                .limit(batchSize).map(StoredMessage::messageId).toList();
        purged.forEach(messages::remove);
        return purged.size();
    }

    @Override
    public void heartbeat(ConsumerHeartbeat heartbeat) {
        heartbeats.put(heartbeat.consumerId(), heartbeat);
    }

    @Override
    public List<ConsumerHeartbeat> liveConsumers(Instant since) {
        return heartbeats.values().stream().filter(beat -> !beat.lastSeenAt().isBefore(since)).toList();
    }

    /** @return every visible message, in position order, with payloads */
    public synchronized List<StoredMessage> all() {
        return List.copyOf(messages.values());
    }

    /** @return the visible message with {@code messageId}, with payload, or {@code null} */
    public synchronized StoredMessage get(String messageId) {
        return messages.get(messageId);
    }

    /** Makes the payload of {@code messageId} undecodable for later leases and payload queries. */
    public void markUnreadable(String messageId) {
        unreadable.add(messageId);
    }

    /** Makes the next call of {@code operation} throw {@code failure} before doing anything. */
    public synchronized void failNext(Operation operation, RuntimeException failure) {
        failures.put(operation, failure);
    }

    /** Makes the next append containing a payload of {@code payloadType} throw {@code failure}. */
    public void failAppendOf(Class<?> payloadType, RuntimeException failure) {
        appendFailures.put(payloadType.getName(), failure);
    }

    /** While {@code paused}, leases return nothing. */
    public void pauseLeasing(boolean paused) {
        leasingPaused = paused;
    }

    private synchronized void injected(Operation operation) {
        RuntimeException failure = failures.remove(operation);
        if (failure != null) {
            throw failure;
        }
    }

    private StoredMessage holder(String consumerId, String messageId) {
        StoredMessage message = messages.get(messageId);
        if (message == null || message.status() != MessageStatus.UNCONSUMED
                || !consumerId.equals(message.leasedBy())) {
            throw new LeaseLostException(messageId, consumerId);
        }
        return message;
    }

    private void consume(StoredMessage message, Instant at) {
        messages.put(message.messageId(), with(message, MessageStatus.CONSUMED, message.attempts(),
                message.nextAttemptAt(), message.lastFailure(), null, null, at, null));
    }

    private void sortByPosition() {
        List<StoredMessage> sorted = new ArrayList<>(messages.values());
        sorted.sort((a, b) -> Long.compare(a.position(), b.position()));
        messages.clear();
        sorted.forEach(message -> messages.put(message.messageId(), message));
    }

    private static Scope scope(LocalTransaction tx) {
        if (tx == null) {
            throw new IllegalArgumentException("tx must not be null");
        }
        return tx.resource(Scope.class).orElseThrow(() -> new IllegalStateException(
                "InMemoryMessageStore needs a transaction of InMemoryTransactionBoundary"));
    }

    private static StoredMessage with(StoredMessage m, MessageStatus status, int attempts, Instant nextAttemptAt,
            DeliveryFailure lastFailure, String leasedBy, Instant leasedUntil, Instant consumedAt, String reason) {
        return new StoredMessage(m.position(), m.messageId(), m.kind(), m.payloadType(), m.payload(), m.commandId(),
                m.orderingKey(), m.sourceSagaType(), m.sourceSagaId(), m.recordedAt(), status, attempts,
                nextAttemptAt, lastFailure, leasedBy, leasedUntil, consumedAt, reason, m.externalRef());
    }
}
