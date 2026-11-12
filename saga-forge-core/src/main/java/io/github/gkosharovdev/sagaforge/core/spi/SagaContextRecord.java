package io.github.gkosharovdev.sagaforge.core.spi;

import java.time.Instant;
import java.util.Map;

/**
 * Persisted {@code SagaContext} values of one saga instance, stored through {@link SagaContextStore} as a
 * record of its own, separate from the {@link SagaInstance} record (FR-024).
 *
 * <p>The record references its saga instance by {@code sagaId} only. It is written independently of the
 * saga-scoped unit of work, so it survives that unit of work's rollback and may exist for a saga whose
 * instance record is {@link SagaStatus#FAILED}.</p>
 *
 * <p>{@code values} holds named values with feature 001 semantics: a scalar, or a structured value
 * represented as a nested {@code Map<String, Object>}. The copy taken on construction is
 * <em>shallow</em>: the top-level map is an unmodifiable defensive copy, but nested structured maps are
 * kept by reference. Callers (the engine) are expected to hand over maps they no longer mutate.</p>
 *
 * <p>The canonical constructor throws {@link IllegalArgumentException} if {@code sagaId}, {@code values}, or {@code lastModifiedAt} is
 *                                  {@code null}, or if {@code values} contains a {@code null} key or value.</p>
 *
 * @param sagaId         the {@link SagaInstance#sagaId()} this record belongs to; non-null
 * @param values         named context values; an unmodifiable shallow defensive copy, with no
 *                       {@code null} keys or values (consistent with {@code SagaContext} rejecting nulls)
 * @param lastModifiedAt time of the last write to the context; non-null
 */
public record SagaContextRecord(String sagaId, Map<String, Object> values, Instant lastModifiedAt) {

    public SagaContextRecord {
        if (sagaId == null) {
            throw new IllegalArgumentException("sagaId must not be null");
        }
        if (values == null) {
            throw new IllegalArgumentException("values must not be null");
        }
        if (lastModifiedAt == null) {
            throw new IllegalArgumentException("lastModifiedAt must not be null");
        }
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (entry.getKey() == null) {
                throw new IllegalArgumentException("values must not contain a null key");
            }
            if (entry.getValue() == null) {
                throw new IllegalArgumentException(
                        "values must not contain a null value, but '" + entry.getKey() + "' is null");
            }
        }
        values = Map.copyOf(values);
    }
}
