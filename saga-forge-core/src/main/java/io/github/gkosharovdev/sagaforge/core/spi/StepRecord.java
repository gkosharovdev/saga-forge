package io.github.gkosharovdev.sagaforge.core.spi;

import java.util.LinkedHashSet;
import java.util.List;

import io.github.gkosharovdev.sagaforge.core.command.CommandId;

/**
 * Persisted record of one {@code @SagaStep} method within a saga instance.
 *
 * @param status            the step's status; non-null
 * @param touchedAggregates aggregates touched by the step, in recording order and without duplicates; an
 *                          unmodifiable defensive copy, empty unless {@code status == COMPLETED}
 * @param attempts          the attempt of the unit of work in which the step completed, counting the first as 1;
 *                          0 unless {@code status == COMPLETED}, then at least 1 ({@code specs/005-step-retry}
 *                          FR-017)
 * @param handledCommands   identities of the commands the step sent and whose handling committed with it, in
 *                          dispatch order; an unmodifiable defensive copy, saga-scoped and without duplicates,
 *                          empty unless {@code status == COMPLETED} ({@code specs/005-step-retry} FR-004)
 * @throws IllegalArgumentException if {@code status}, {@code touchedAggregates}, {@code handledCommands} or any of
 *                                  their elements is {@code null}, if aggregates, commands or attempts are given
 *                                  for a step that is not {@link StepStatus#COMPLETED}, if a completed step has
 *                                  fewer than 1 attempt, or if {@code handledCommands} holds an unscoped or
 *                                  duplicate identity
 */
public record StepRecord(StepStatus status, List<AggregateReference> touchedAggregates, int attempts,
        List<CommandId> handledCommands) {

    private static final StepRecord NOT_RUN = new StepRecord(StepStatus.NOT_RUN, List.of());

    public StepRecord {
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        touchedAggregates = copyWithoutNulls(touchedAggregates, "touchedAggregates");
        handledCommands = copyWithoutNulls(handledCommands, "handledCommands");
        boolean completed = status == StepStatus.COMPLETED;
        if (!completed && !touchedAggregates.isEmpty()) {
            throw new IllegalArgumentException(
                    "touchedAggregates must be empty unless status is COMPLETED, but status is " + status);
        }
        if (!completed && !handledCommands.isEmpty()) {
            throw new IllegalArgumentException(
                    "handledCommands must be empty unless status is COMPLETED, but status is " + status);
        }
        if (completed ? attempts < 1 : attempts != 0) {
            throw new IllegalArgumentException("attempts must be " + (completed ? "at least 1" : "0")
                    + " when status is " + status + ", but is " + attempts);
        }
        for (CommandId id : handledCommands) {
            if (!id.sagaScoped()) {
                throw new IllegalArgumentException("handledCommands must hold saga-scoped identities only: " + id);
            }
        }
        if (new LinkedHashSet<>(handledCommands).size() != handledCommands.size()) {
            throw new IllegalArgumentException("handledCommands must not contain duplicates: " + handledCommands);
        }
        touchedAggregates = List.copyOf(new LinkedHashSet<>(touchedAggregates));
    }

    /**
     * A record without attempts or commands: attempt 1 if {@code status == COMPLETED}, otherwise 0.
     *
     * @param status            the step's status; non-null
     * @param touchedAggregates aggregates touched by the step; empty unless {@code status == COMPLETED}
     * @throws IllegalArgumentException as for the canonical constructor
     */
    public StepRecord(StepStatus status, List<AggregateReference> touchedAggregates) {
        this(status, touchedAggregates, status == StepStatus.COMPLETED ? 1 : 0, List.of());
    }

    /**
     * @return a record for a step that has not run (or whose unit of work rolled back)
     */
    public static StepRecord notRun() {
        return NOT_RUN;
    }

    /**
     * @param touchedAggregates aggregates touched by the step, in recording order; duplicates are dropped
     * @return a record for a step completed on the first attempt without recorded commands
     * @throws IllegalArgumentException if {@code touchedAggregates} or any of its elements is {@code null}
     */
    public static StepRecord completed(List<AggregateReference> touchedAggregates) {
        return new StepRecord(StepStatus.COMPLETED, touchedAggregates);
    }

    /**
     * @param touchedAggregates aggregates touched by the step, in recording order; duplicates are dropped
     * @param attempts          the attempt in which the step completed; at least 1
     * @param handledCommands   identities of the commands it sent, in dispatch order
     * @return a record for a completed step
     * @throws IllegalArgumentException as for the canonical constructor
     */
    public static StepRecord completed(List<AggregateReference> touchedAggregates, int attempts,
            List<CommandId> handledCommands) {
        return new StepRecord(StepStatus.COMPLETED, touchedAggregates, attempts, handledCommands);
    }

    private static <T> List<T> copyWithoutNulls(List<T> list, String name) {
        if (list == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        for (T element : list) {
            if (element == null) {
                throw new IllegalArgumentException(name + " must not contain null elements");
            }
        }
        return List.copyOf(list);
    }
}
