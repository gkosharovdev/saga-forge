package io.github.gkosharovdev.sagaforge.core.spi;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * Persisted record of one {@code @SagaStep} method within a saga instance.
 *
 * @param status            the step's status; non-null
 * @param touchedAggregates aggregates touched by the step, in recording order and without duplicates; an
 *                          unmodifiable defensive copy, empty unless {@code status == COMPLETED}
 * @throws IllegalArgumentException if {@code status}, {@code touchedAggregates}, or any of its elements is
 *                                  {@code null}, or if aggregates are given for a step that is not
 *                                  {@link StepStatus#COMPLETED}
 */
public record StepRecord(StepStatus status, List<AggregateReference> touchedAggregates) {

    private static final StepRecord NOT_RUN = new StepRecord(StepStatus.NOT_RUN, List.of());

    public StepRecord {
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        if (touchedAggregates == null) {
            throw new IllegalArgumentException("touchedAggregates must not be null");
        }
        for (AggregateReference reference : touchedAggregates) {
            if (reference == null) {
                throw new IllegalArgumentException("touchedAggregates must not contain null elements");
            }
        }
        if (status != StepStatus.COMPLETED && !touchedAggregates.isEmpty()) {
            throw new IllegalArgumentException(
                    "touchedAggregates must be empty unless status is COMPLETED, but status is " + status);
        }
        touchedAggregates = List.copyOf(new LinkedHashSet<>(touchedAggregates));
    }

    /**
     * @return a record for a step that has not run (or whose unit of work rolled back)
     */
    public static StepRecord notRun() {
        return NOT_RUN;
    }

    /**
     * @param touchedAggregates aggregates touched by the step, in recording order; duplicates are dropped
     * @return a record for a completed step
     * @throws IllegalArgumentException if {@code touchedAggregates} or any of its elements is {@code null}
     */
    public static StepRecord completed(List<AggregateReference> touchedAggregates) {
        return new StepRecord(StepStatus.COMPLETED, touchedAggregates);
    }
}
