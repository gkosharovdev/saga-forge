package io.github.gkosharovdev.sagaforge.core.spi;

import java.time.Instant;
import java.util.Map;

/**
 * Represents a running saga instance's persisted state.
 *
 * @param sagaId           unique identifier
 * @param sagaType         fully qualified class name of the @Saga class
 * @param state            serialized saga field state
 * @param associations     association property -> value mappings
 * @param status           current lifecycle status
 * @param createdAt        creation timestamp
 * @param lastModifiedAt   last state transition timestamp
 */
public record SagaInstance(
    String sagaId,
    String sagaType,
    Map<String, Object> state,
    Map<String, String> associations,
    SagaStatus status,
    Instant createdAt,
    Instant lastModifiedAt
) {
    public SagaInstance {
        state = Map.copyOf(state);
        associations = Map.copyOf(associations);
    }
}
