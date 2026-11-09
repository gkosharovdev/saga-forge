package io.github.gkosharovdev.sagaforge.core.spi;

import java.time.Instant;
import java.util.List;

/**
 * One chronological transition of a saga instance's lifecycle history. Storage-agnostic vocabulary: the engine
 * produces the entries and hands them to {@link StorageProvider#save(SagaInstance, List)} with the instance write
 * they belong to (the creation write, a unit of work's write, or the {@code FAILED} write), in the same
 * transaction. Storage persists them for direct data-store inspection; there is no query API for them.
 *
 * <p>The persisted order of one {@code sagaId}'s entries is the order in which they were handed over: storage
 * assigns each entry its position when it inserts it, after every entry already recorded for the instance. There is
 * no tiebreak and no first-save limitation: every completed step gets its entry in true execution order.</p>
 *
 * @param sagaId            the owning instance's {@link SagaInstance#sagaId()}; non-null
 * @param kind              the transition; non-null
 * @param stepName          the completed step; non-null if and only if {@code kind == STEP_COMPLETED}
 * @param touchedAggregates the completed step's {@link StepRecord#touchedAggregates()}; non-null, with no
 *                          {@code null} elements, empty unless {@code kind == STEP_COMPLETED}; an unmodifiable
 *                          defensive copy
 * @param outcomeStep       the reached {@code @EndSaga} step ({@link SagaInstance#outcome()}); non-null if and
 *                          only if {@code kind == COMPLETED}
 * @param failure           the recorded {@link SagaInstance#failure()}; non-null if and only if
 *                          {@code kind == FAILED}
 * @param occurredAt        the clock instant of the transition; non-null
 * @throws IllegalArgumentException if {@code sagaId}, {@code kind}, {@code occurredAt}, {@code touchedAggregates}
 *                                  or one of its elements is {@code null}, or if {@code stepName},
 *                                  {@code outcomeStep}, {@code failure} or a non-empty {@code touchedAggregates} is
 *                                  present or absent inconsistently with {@code kind}
 */
public record SagaLifecycleEntry(
    String sagaId,
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
