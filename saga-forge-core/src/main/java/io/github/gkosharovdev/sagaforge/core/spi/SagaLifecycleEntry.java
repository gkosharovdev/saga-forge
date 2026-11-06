package io.github.gkosharovdev.sagaforge.core.spi;

import java.time.Instant;
import java.util.List;

/**
 * One chronological transition of a saga instance's lifecycle history. Storage-agnostic vocabulary: each
 * storage module derives entries inside its {@link StorageProvider#save(SagaInstance)} by diffing the previously
 * persisted snapshot against the incoming one, and persists them for direct data-store inspection. No SPI method
 * accepts or returns this type; there is no query API for it.
 *
 * <p>Entries of one {@code sagaId} are totally ordered by {@code sequenceNumber}, ascending and unique per
 * {@code sagaId} (not globally).</p>
 *
 * <p><b>Known limitation</b>: when one {@code save()} completes more than one step, their
 * {@link SagaLifecycleEventKind#STEP_COMPLETED} entries are ordered alphabetically by {@code stepName}. That is a
 * deterministic tiebreak, not the true execution order, which {@link SagaInstance#steps()} does not carry. A step
 * already {@link StepStatus#COMPLETED} in an instance's first {@code save()} gets no entry, and
 * {@link SagaLifecycleEventKind#CREATED} is recorded whatever the first status is.</p>
 *
 * @param sagaId            the owning instance's {@link SagaInstance#sagaId()}; non-null
 * @param sequenceNumber    position in the instance's history; non-negative
 * @param kind              the transition; non-null
 * @param stepName          the completed step; non-null if and only if {@code kind == STEP_COMPLETED}
 * @param touchedAggregates the completed step's {@link StepRecord#touchedAggregates()}; non-null, with no
 *                          {@code null} elements, empty unless {@code kind == STEP_COMPLETED}; an unmodifiable
 *                          defensive copy
 * @param outcomeStep       the reached {@code @EndSaga} step ({@link SagaInstance#outcome()}); non-null if and
 *                          only if {@code kind == COMPLETED}
 * @param failure           the recorded {@link SagaInstance#failure()}; non-null if and only if
 *                          {@code kind == FAILED}
 * @param occurredAt        the producing snapshot's {@link SagaInstance#lastModifiedAt()} (its
 *                          {@link SagaInstance#createdAt()} for {@code CREATED}); non-null
 * @throws IllegalArgumentException if {@code sagaId}, {@code kind}, {@code occurredAt}, {@code touchedAggregates}
 *                                  or one of its elements is {@code null}, if {@code sequenceNumber} is negative,
 *                                  or if {@code stepName}, {@code outcomeStep}, {@code failure} or a non-empty
 *                                  {@code touchedAggregates} is present or absent inconsistently with {@code kind}
 */
public record SagaLifecycleEntry(
    String sagaId,
    long sequenceNumber,
    SagaLifecycleEventKind kind,
    String stepName,
    List<AggregateReference> touchedAggregates,
    String outcomeStep,
    SagaFailure failure,
    Instant occurredAt
) {
    public SagaLifecycleEntry {
        if (sagaId == null) {
            throw new IllegalArgumentException("sagaId must not be null");
        }
        if (sequenceNumber < 0) {
            throw new IllegalArgumentException("sequenceNumber must not be negative, but is " + sequenceNumber);
        }
        if (kind == null) {
            throw new IllegalArgumentException("kind must not be null");
        }
        if (occurredAt == null) {
            throw new IllegalArgumentException("occurredAt must not be null");
        }
        if (touchedAggregates == null) {
            throw new IllegalArgumentException("touchedAggregates must not be null");
        }
        for (AggregateReference reference : touchedAggregates) {
            if (reference == null) {
                throw new IllegalArgumentException("touchedAggregates must not contain null elements");
            }
        }
        requirePresentIff("stepName", stepName, SagaLifecycleEventKind.STEP_COMPLETED, kind);
        requirePresentIff("outcomeStep", outcomeStep, SagaLifecycleEventKind.COMPLETED, kind);
        requirePresentIff("failure", failure, SagaLifecycleEventKind.FAILED, kind);
        if (kind != SagaLifecycleEventKind.STEP_COMPLETED && !touchedAggregates.isEmpty()) {
            throw new IllegalArgumentException(
                "touchedAggregates must be empty unless kind is STEP_COMPLETED, but kind is " + kind);
        }
        touchedAggregates = List.copyOf(touchedAggregates);
    }

    private static void requirePresentIff(String name, Object value, SagaLifecycleEventKind impliedBy,
                                          SagaLifecycleEventKind kind) {
        if ((kind == impliedBy) != (value != null)) {
            throw new IllegalArgumentException(
                name + " must be non-null if and only if kind is " + impliedBy + ", but kind is " + kind
                    + " and " + name + " is " + value);
        }
    }
}
