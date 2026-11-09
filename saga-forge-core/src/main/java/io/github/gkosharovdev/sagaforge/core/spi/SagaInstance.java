package io.github.gkosharovdev.sagaforge.core.spi;

import java.time.Instant;
import java.util.Map;

/**
 * Represents a running saga instance's persisted state.
 *
 * <p>{@code steps}, {@code outcome} and {@code status} commit together with the step's unit of work.
 * {@code failure} and the {@link SagaStatus#FAILED} status are written after a rollback, in their own
 * write. {@code SagaContext} values are not held here; they live in a {@link SagaContextRecord}.</p>
 *
 * @param sagaId           unique identifier
 * @param sagaType         fully qualified class name of the @Saga class
 * @param state            reserved; not populated by the engine. Values that must survive between steps belong
 *                         in {@code SagaContext} (a {@link SagaContextRecord}); an unmodifiable defensive copy
 * @param associations     association property -> value mappings; an unmodifiable defensive copy
 * @param status           current lifecycle status
 * @param createdAt        creation timestamp
 * @param lastModifiedAt   last state transition timestamp
 * @param steps            one entry per {@code @SagaStep} method name; an unmodifiable defensive copy with no
 *                         {@code null} keys or values
 * @param outcome          name of the reached {@code @EndSaga} step; non-null if and only if
 *                         {@code status == COMPLETED}
 * @param failure          the recorded failure; non-null if and only if {@code status == FAILED}
 * @throws IllegalArgumentException if {@code steps} is {@code null} or contains a {@code null} key or value,
 *                                  if {@code outcome} is present without {@code COMPLETED} (or absent with it),
 *                                  or if {@code failure} is present without {@code FAILED} (or absent with it)
 */
public record SagaInstance(
    String sagaId,
    String sagaType,
    Map<String, Object> state,
    Map<String, String> associations,
    SagaStatus status,
    Instant createdAt,
    Instant lastModifiedAt,
    Map<String, StepRecord> steps,
    String outcome,
    SagaFailure failure
) {
    public SagaInstance {
        state = Map.copyOf(state);
        associations = Map.copyOf(associations);
        steps = copySteps(steps);
        boolean completed = status == SagaStatus.COMPLETED;
        if (completed != (outcome != null)) {
            throw new IllegalArgumentException(
                "outcome must be non-null if and only if status is COMPLETED, but status is " + status
                    + " and outcome is " + outcome);
        }
        boolean failed = status == SagaStatus.FAILED;
        if (failed != (failure != null)) {
            throw new IllegalArgumentException(
                "failure must be non-null if and only if status is FAILED, but status is " + status
                    + " and failure is " + failure);
        }
    }

    private static Map<String, StepRecord> copySteps(Map<String, StepRecord> steps) {
        if (steps == null) {
            throw new IllegalArgumentException("steps must not be null");
        }
        for (Map.Entry<String, StepRecord> entry : steps.entrySet()) {
            if (entry.getKey() == null) {
                throw new IllegalArgumentException("steps must not contain null keys");
            }
            if (entry.getValue() == null) {
                throw new IllegalArgumentException("steps must not contain null values (step " + entry.getKey() + ")");
            }
        }
        return Map.copyOf(steps);
    }
}
