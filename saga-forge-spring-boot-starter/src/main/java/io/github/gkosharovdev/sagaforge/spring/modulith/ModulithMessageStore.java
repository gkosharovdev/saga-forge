package io.github.gkosharovdev.sagaforge.spring.modulith;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.modulith.events.core.EventPublicationRepository;
import org.springframework.modulith.events.core.EventSerializer;
import org.springframework.modulith.events.core.PublicationTargetIdentifier;
import org.springframework.modulith.events.core.TargetEventPublication;

import io.github.gkosharovdev.sagaforge.core.spi.LocalTransaction;
import io.github.gkosharovdev.sagaforge.core.spi.store.LeasedMessages;
import io.github.gkosharovdev.sagaforge.core.spi.store.ConsumerHeartbeat;
import io.github.gkosharovdev.sagaforge.core.spi.store.DeliveryFailure;
import io.github.gkosharovdev.sagaforge.core.spi.store.NewMessage;
import io.github.gkosharovdev.sagaforge.core.spi.store.StoredMessage;
import io.github.gkosharovdev.sagaforge.core.spi.store.MessageStore;
import io.github.gkosharovdev.sagaforge.core.spi.store.MessageQuery;
import io.github.gkosharovdev.sagaforge.core.spi.store.UnreadableMessageException;
import io.github.gkosharovdev.sagaforge.storage.postgres.PostgresMessageStore;

/**
 * Spring Modulith interop ({@code specs/007-in-memory-transport} FR-028, FR-028a, research §10): the messages live in
 * Spring Modulith's event publication registry, under one target identifier no listener has, and Saga Forge keeps its
 * delivery records (attempts, failures, set-aside, order) beside them in {@code saga_message_store} with no payload.
 *
 * <p>Uses the registry only to store, list, complete, fail and purge publications, all by publication identity
 * ({@link EventPublicationRepository}); everything else delegates to the {@link PostgresMessageStore}. The registry and
 * the delivery records are written in the same Spring transaction.</p>
 */
public final class ModulithMessageStore implements MessageStore {

    /** The registry target identifier of Saga Forge's messages; no listener is registered for it. */
    public static final PublicationTargetIdentifier TARGET =
            PublicationTargetIdentifier.of("io.github.gkosharovdev.sagaforge.transport");

    private final EventPublicationRepository registry;
    private final EventSerializer serializer;
    private final PostgresMessageStore records;
    private final ClassLoader payloadClassLoader;
    private final Clock clock;

    public ModulithMessageStore(EventPublicationRepository registry, EventSerializer serializer,
            PostgresMessageStore records, ClassLoader payloadClassLoader, Clock clock) {
        this.registry = registry;
        this.serializer = serializer;
        this.records = records;
        this.payloadClassLoader = payloadClassLoader;
        this.clock = clock;
    }

    @Override
    public void append(LocalTransaction tx, List<NewMessage> entries) {
        for (NewMessage entry : entries) {
            SagaForgeEnvelope envelope = new SagaForgeEnvelope(entry.messageId(), entry.kind().name(),
                    entry.payloadType(), String.valueOf(serializer.serialize(entry.payload())));
            TargetEventPublication publication = registry.create(TargetEventPublication.of(envelope, TARGET,
                    clock.instant()));
            records.appendDeliveryRecord(tx, entry, publication.getIdentifier().toString());
        }
    }

    @Override
    public LeasedMessages lease(String consumerId, Instant now, Duration leaseDuration, int limit) {
        LeasedMessages leased = records.lease(consumerId, now, leaseDuration, limit);
        if (leased.readable().isEmpty()) {
            return leased;
        }
        Map<String, SagaForgeEnvelope> envelopes = registry.findIncompletePublications().stream()
                .filter(p -> p.isIdentifiedBy(TARGET) && p.getEvent() instanceof SagaForgeEnvelope)
                .collect(Collectors.toMap(p -> p.getIdentifier().toString(), p -> (SagaForgeEnvelope) p.getEvent(),
                        (a, b) -> a));
        List<StoredMessage> readable = new ArrayList<>();
        List<UnreadableMessageException> unreadable = new ArrayList<>(leased.unreadable());
        for (StoredMessage message : leased.readable()) {
            SagaForgeEnvelope envelope = envelopes.get(message.externalRef());
            try {
                if (envelope == null) {
                    throw new IllegalStateException("PUBLICATION_MISSING: no incomplete registry publication "
                            + message.externalRef());
                }
                Class<?> type = Class.forName(envelope.payloadType(), false, payloadClassLoader);
                readable.add(message.withPayload(serializer.deserialize(envelope.payload(), type)));
            } catch (ClassNotFoundException | RuntimeException e) {
                unreadable.add(new UnreadableMessageException(message, e));
            }
        }
        return new LeasedMessages(readable, unreadable);
    }

    @Override
    public Set<String> renew(String consumerId, Collection<String> messageIds, Instant until) {
        return records.renew(consumerId, messageIds, until);
    }

    @Override
    public void markConsumed(String consumerId, String messageId, Instant at) {
        String publication = publicationOf(messageId);
        records.markConsumed(consumerId, messageId, at);
        registry.markCompleted(UUID.fromString(publication), at);
    }

    @Override
    public void markConsumed(LocalTransaction tx, String consumerId, String messageId, Instant at) {
        String publication = publicationOf(messageId);
        records.markConsumed(tx, consumerId, messageId, at);
        registry.markCompleted(UUID.fromString(publication), at);
    }

    @Override
    public void recordFailure(String consumerId, String messageId, DeliveryFailure failure, Instant nextAttemptAt) {
        records.recordFailure(consumerId, messageId, failure, nextAttemptAt);
    }

    @Override
    public void setAside(String consumerId, String messageId, DeliveryFailure failure, String reason) {
        String publication = publicationOf(messageId);
        records.setAside(consumerId, messageId, failure, reason);
        registry.markFailed(UUID.fromString(publication));
    }

    @Override
    public void release(String consumerId) {
        records.release(consumerId);
    }

    @Override
    public List<StoredMessage> query(MessageQuery query) {
        List<StoredMessage> found = records.query(new MessageQuery(query.statuses(), query.sourceSagaType(),
                query.sourceSagaId(), query.orderingKey(), query.payloadType(), query.limit(), false));
        if (!query.includePayload()) {
            return found;
        }
        Map<String, TargetEventPublication> all = registry.findIncompletePublications().stream()
                .filter(p -> p.isIdentifiedBy(TARGET))
                .collect(Collectors.toMap(p -> p.getIdentifier().toString(), Function.identity(), (a, b) -> a));
        all.putAll(registry.findCompletedPublications().stream().filter(p -> p.isIdentifiedBy(TARGET))
                .collect(Collectors.toMap(p -> p.getIdentifier().toString(), Function.identity(), (a, b) -> a)));
        List<StoredMessage> withPayload = new ArrayList<>();
        for (StoredMessage message : found) {
            TargetEventPublication publication = all.get(message.externalRef());
            if (publication == null || !(publication.getEvent() instanceof SagaForgeEnvelope envelope)) {
                continue;
            }
            try {
                Class<?> type = Class.forName(envelope.payloadType(), false, payloadClassLoader);
                withPayload.add(message.withPayload(serializer.deserialize(envelope.payload(), type)));
            } catch (ClassNotFoundException | RuntimeException unreadable) {
                // Left out: a diagnostic query never fails on an unreadable payload.
            }
        }
        return withPayload;
    }

    @Override
    public boolean redeliver(String messageId, Instant now) {
        return records.redeliver(messageId, now);
    }

    @Override
    public int purgeConsumed(Instant cutoff, int batchSize) {
        int purged = records.purgeConsumed(cutoff, batchSize);
        registry.deleteCompletedPublicationsBefore(cutoff);
        return purged;
    }

    @Override
    public void heartbeat(ConsumerHeartbeat heartbeat) {
        records.heartbeat(heartbeat);
    }

    @Override
    public List<ConsumerHeartbeat> liveConsumers(Instant since) {
        return records.liveConsumers(since);
    }

    private String publicationOf(String messageId) {
        return records.externalRef(messageId)
                .orElseThrow(() -> new IllegalStateException("no delivery record for message " + messageId));
    }
}
