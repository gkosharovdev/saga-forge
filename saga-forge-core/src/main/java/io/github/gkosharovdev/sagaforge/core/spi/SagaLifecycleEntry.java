package io.github.gkosharovdev.sagaforge.core.spi;

import io.github.gkosharovdev.sagaforge.core.compensation.CompensationTrigger;
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
 * @param stepName          the completed step, for {@code ATTEMPT_FAILED} the step that failed, for
 *                          {@code STEP_COMPENSATED} and {@code COMPENSATION_FAILED} the compensated step; non-null if
 *                          and only if {@code kind} is one of those four
 * @param touchedAggregates the completed step's {@link StepRecord#touchedAggregates()}; non-null, with no
 *                          {@code null} elements, empty unless {@code kind == STEP_COMPLETED}; an unmodifiable
 *                          defensive copy
 * @param outcomeStep       the reached {@code @EndSaga} step ({@link SagaInstance#outcome()}); non-null if and
 *                          only if {@code kind == COMPLETED}
 * @param failure           the recorded {@link SagaInstance#failure()}, or for {@code ATTEMPT_FAILED} the failure of
 *                          the attempt, or for {@code COMPENSATION_FAILED} the compensation's failure; non-null if
 *                          and only if {@code kind} is {@code FAILED}, {@code ATTEMPT_FAILED} or
 *                          {@code COMPENSATION_FAILED}
 * @param occurredAt        the clock instant of the transition; non-null
 * @param trigger           the recorded {@link SagaInstance#compensationTrigger()}; non-null if and only if
 *                          {@code kind == COMPENSATION_STARTED}
 * @throws IllegalArgumentException if {@code sagaId}, {@code kind}, {@code occurredAt}, {@code touchedAggregates}
 *                                  or one of its elements is {@code null}, or if {@code stepName},
 *                                  {@code outcomeStep}, {@code failure}, {@code trigger} or a non-empty {@code touchedAggregates} is
 *                                  present or absent inconsistently with {@code kind}
 */
public record SagaLifecycleEntry(
    String sagaId,
    SagaLifecycleEventKind kind,
    String stepName,
    List<AggregateReference> touchedAggregates,
    String outcomeStep,
    SagaFailure failure,
    Instant occurredAt,
    CompensationTrigger trigger
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
        requirePresentIff("stepName", stepName, kind == SagaLifecycleEventKind.STEP_COMPLETED
            || kind == SagaLifecycleEventKind.ATTEMPT_FAILED || kind == SagaLifecycleEventKind.STEP_COMPENSATED
            || kind == SagaLifecycleEventKind.COMPENSATION_FAILED,
            "STEP_COMPLETED, ATTEMPT_FAILED, STEP_COMPENSATED or COMPENSATION_FAILED", kind);
        requirePresentIff("outcomeStep", outcomeStep, kind == SagaLifecycleEventKind.COMPLETED, "COMPLETED", kind);
        requirePresentIff("failure", failure, kind == SagaLifecycleEventKind.FAILED
            || kind == SagaLifecycleEventKind.ATTEMPT_FAILED || kind == SagaLifecycleEventKind.COMPENSATION_FAILED,
            "FAILED, ATTEMPT_FAILED or COMPENSATION_FAILED", kind);
        requirePresentIff("trigger", trigger, kind == SagaLifecycleEventKind.COMPENSATION_STARTED,
            "COMPENSATION_STARTED", kind);
        if (kind != SagaLifecycleEventKind.STEP_COMPLETED && !touchedAggregates.isEmpty()) {
            throw new IllegalArgumentException(
                "touchedAggregates must be empty unless kind is STEP_COMPLETED, but kind is " + kind);
        }
        touchedAggregates = List.copyOf(touchedAggregates);
    }

    /**
     * An entry without a compensation trigger.
     *
     * @throws IllegalArgumentException as for the canonical constructor
     */
    public SagaLifecycleEntry(String sagaId, SagaLifecycleEventKind kind, String stepName,
            List<AggregateReference> touchedAggregates, String outcomeStep, SagaFailure failure, Instant occurredAt) {
        this(sagaId, kind, stepName, touchedAggregates, outcomeStep, failure, occurredAt, null);
    }

    private static void requirePresentIff(String name, Object value, boolean required, String requiringKinds,
                                          SagaLifecycleEventKind kind) {
        if (required != (value != null)) {
            throw new IllegalArgumentException(
                name + " must be non-null if and only if kind is " + requiringKinds + ", but kind is " + kind
                    + " and " + name + " is " + value);
        }
    }
}
