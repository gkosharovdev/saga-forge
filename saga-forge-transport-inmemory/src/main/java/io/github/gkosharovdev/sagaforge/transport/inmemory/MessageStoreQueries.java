package io.github.gkosharovdev.sagaforge.transport.inmemory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.gkosharovdev.sagaforge.core.spi.store.StoredMessage;
import io.github.gkosharovdev.sagaforge.core.spi.store.MessageStore;
import io.github.gkosharovdev.sagaforge.core.spi.store.MessageQuery;
import io.github.gkosharovdev.sagaforge.core.spi.store.MessageStatus;
import io.github.gkosharovdev.sagaforge.transport.inmemory.internal.Relay;

/**
 * Operator view of the message store ({@code specs/007-in-memory-transport} FR-022, FR-023): read-only queries and the
 * redelivery of set-aside messages. Obtained from {@link InMemoryTransport#queries()}.
 */
public final class MessageStoreQueries {

    private final MessageStore messageStore;
    private final Relay relay;
    private final Clock clock;

    MessageStoreQueries(MessageStore messageStore, Relay relay, Clock clock) {
        this.messageStore = messageStore;
        this.relay = relay;
        this.clock = clock;
    }

    /** @return the messages matching {@code query}, in position order */
    public List<StoredMessage> find(MessageQuery query) {
        if (query == null) {
            throw new IllegalArgumentException("query must not be null");
        }
        return messageStore.query(query);
    }

    /**
     * Delivers a set-aside message again, as if it were new (FR-023).
     *
     * @return {@code false} if no message with that id is set aside
     */
    public boolean redeliver(String messageId) {
        if (messageId == null || messageId.isBlank()) {
            throw new IllegalArgumentException("messageId must not be blank");
        }
        boolean redelivered = messageStore.redeliver(messageId, clock.instant());
        if (redelivered) {
            relay.signal();
        }
        return redelivered;
    }

    /**
     * Counts per status (at most {@link MessageQuery#MAX_LIMIT} each) and the age of the oldest unconsumed message.
     *
     * @return the summary
     */
    public Status status() {
        Map<MessageStatus, Integer> counts = new EnumMap<>(MessageStatus.class);
        Optional<Instant> oldest = Optional.empty();
        for (MessageStatus status : MessageStatus.values()) {
            List<StoredMessage> found = messageStore.query(MessageQuery.builder().statuses(status)
                    .limit(MessageQuery.MAX_LIMIT).build());
            counts.put(status, found.size());
            if (status == MessageStatus.UNCONSUMED && !found.isEmpty()) {
                oldest = Optional.of(found.get(0).recordedAt());
            }
        }
        Optional<Duration> age = oldest.map(at -> Duration.between(at, clock.instant()));
        return new Status(Map.copyOf(counts), age);
    }

    /**
     * @param counts            messages per status, capped at {@link MessageQuery#MAX_LIMIT}
     * @param oldestUnconsumed age of the oldest unconsumed message, if any
     */
    public record Status(Map<MessageStatus, Integer> counts, Optional<Duration> oldestUnconsumed) {
    }
}
