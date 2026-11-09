package io.github.gkosharovdev.sagaforge.core.spi;

import io.github.gkosharovdev.sagaforge.core.compensation.CompensationTrigger;
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
 * @param pendingRetry     the attempt the engine claimed for a retry of the current unit of work; nullable, and
 *                         {@code null} unless {@code status} is {@code CREATED} or {@code RUNNING}
 *                         ({@code specs/005-step-retry} FR-015). Written in its own transaction, like
 *                         {@code failure}
 * @param compensationTrigger why the instance entered compensation; non-null if and only if {@code status} is
 *                         {@code COMPENSATING}, {@code COMPENSATED} or {@code REQUIRES_INTERVENTION}
 *                         ({@code specs/006-saga-compensation} FR-017). Written once, by the write that enters
 *                         {@code COMPENSATING}
 * @throws IllegalArgumentException if {@code steps} is {@code null} or contains a {@code null} key or value,
 *                                  if {@code outcome} is present without {@code COMPLETED} (or absent with it),
 *                                  if {@code failure} is present without {@code FAILED} (or absent with it), or if
 *                                  {@code pendingRetry} is present on an instance that is not {@code CREATED} or
 *                                  {@code RUNNING}, if
 *                                  {@code compensationTrigger} breaks its rule, if a step has a compensation state
 *                                  outside compensation, if a {@code COMPENSATED} instance has a step not yet
 *                                  compensated or failed, or if a {@code REQUIRES_INTERVENTION} instance does not
 *                                  have exactly one failed compensation
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
    SagaFailure failure,
    PendingRetry pendingRetry,
    CompensationTrigger compensationTrigger
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
        boolean active = status == SagaStatus.CREATED || status == SagaStatus.RUNNING;
        if (pendingRetry != null && !active) {
            throw new IllegalArgumentException(
                "pendingRetry must be null unless status is CREATED or RUNNING, but status is " + status);
        }
        boolean compensation = isCompensation(status);
        if (compensation != (compensationTrigger != null)) {
            throw new IllegalArgumentException("compensationTrigger must be non-null if and only if status is "
                + "COMPENSATING, COMPENSATED or REQUIRES_INTERVENTION, but status is " + status);
        }
        requireCompensationStates(status, steps);
    }

    /**
     * An instance without compensation.
     *
     * @throws IllegalArgumentException as for the canonical constructor
     */
    public SagaInstance(String sagaId, String sagaType, Map<String, Object> state, Map<String, String> associations,
            SagaStatus status, Instant createdAt, Instant lastModifiedAt, Map<String, StepRecord> steps,
            String outcome, SagaFailure failure, PendingRetry pendingRetry) {
        this(sagaId, sagaType, state, associations, status, createdAt, lastModifiedAt, steps, outcome, failure,
            pendingRetry, null);
    }

    /**
     * An instance without a pending retry.
     *
     * @throws IllegalArgumentException as for the canonical constructor
     */
    public SagaInstance(String sagaId, String sagaType, Map<String, Object> state, Map<String, String> associations,
            SagaStatus status, Instant createdAt, Instant lastModifiedAt, Map<String, StepRecord> steps,
            String outcome, SagaFailure failure) {
        this(sagaId, sagaType, state, associations, status, createdAt, lastModifiedAt, steps, outcome, failure,
            null, null);
    }

    /**
     * @param retry the pending retry to record; nullable
     * @return this instance with {@code pendingRetry} replaced
     * @throws IllegalArgumentException if {@code retry} is non-null and the status is not {@code CREATED} or
     *                                  {@code RUNNING}
     */
    public SagaInstance withPendingRetry(PendingRetry retry) {
        return new SagaInstance(sagaId, sagaType, state, associations, status, createdAt, lastModifiedAt, steps,
            outcome, failure, retry, compensationTrigger);
    }

    /**
     * @param trigger the compensation trigger to record; nullable
     * @return this instance with {@code compensationTrigger} replaced
     * @throws IllegalArgumentException as for the canonical constructor
     */
    public SagaInstance withCompensationTrigger(CompensationTrigger trigger) {
        return new SagaInstance(sagaId, sagaType, state, associations, status, createdAt, lastModifiedAt, steps,
            outcome, failure, pendingRetry, trigger);
    }

    /** {@code true} for {@code COMPENSATING}, {@code COMPENSATED} and {@code REQUIRES_INTERVENTION}. */
    public static boolean isCompensation(SagaStatus status) {
        return status == SagaStatus.COMPENSATING || status == SagaStatus.COMPENSATED
            || status == SagaStatus.REQUIRES_INTERVENTION;
    }

    private static void requireCompensationStates(SagaStatus status, Map<String, StepRecord> steps) {
        int failed = 0;
        for (Map.Entry<String, StepRecord> entry : steps.entrySet()) {
            StepCompensation compensation = entry.getValue().compensation();
            if (compensation == null) {
                continue;
            }
            if (!isCompensation(status)) {
                throw new IllegalArgumentException("step " + entry.getKey() + " must have no compensation state "
                    + "unless status is COMPENSATING, COMPENSATED or REQUIRES_INTERVENTION, but status is " + status);
            }
            CompensationStatus state = compensation.status();
            if (status == SagaStatus.COMPENSATED
                    && (state == CompensationStatus.NOT_ATTEMPTED || state == CompensationStatus.FAILED)) {
                throw new IllegalArgumentException("step " + entry.getKey() + " of a COMPENSATED instance must not be "
                    + state);
            }
            if (state == CompensationStatus.FAILED) {
                failed++;
            }
        }
        boolean intervention = status == SagaStatus.REQUIRES_INTERVENTION;
        if (intervention ? failed != 1 : failed != 0) {
            throw new IllegalArgumentException("exactly one step must have a FAILED compensation if and only if "
                + "status is REQUIRES_INTERVENTION, but status is " + status + " with " + failed);
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
