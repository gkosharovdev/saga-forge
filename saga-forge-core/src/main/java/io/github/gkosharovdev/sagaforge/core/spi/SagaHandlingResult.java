package io.github.gkosharovdev.sagaforge.core.spi;

import java.util.List;

/**
 * The result of handling one event for one saga type or instance, as reported in a {@link HandlingReport}.
 *
 * @param sagaType        fully qualified class name of the {@code @Saga} class; non-null
 * @param sagaId          the instance; {@code null} for {@link HandlingOutcome#UNMATCHED}, for
 *                        {@link HandlingOutcome#DUPLICATE_START} and for a start that raised before its creation
 *                        write
 * @param outcome         what happened; non-null
 * @param started         {@code true} if and only if this event's creation write committed a new instance
 * @param outcomeStep     the {@code @EndSaga} step that ended the instance; non-null if and only if
 *                        {@code outcome == COMPLETED}
 * @param failure         the failure recorded on the instance; non-null if and only if {@code outcome == FAILED}
 * @param unitOfWorkId    the unit of work that ran; non-null if and only if one ran ({@code WAITING},
 *                        {@code COMPLETED}, {@code FAILED}, {@code NOT_HANDLED} after a conflict, and for
 *                        {@code COMPENSATED} and {@code REQUIRES_INTERVENTION} the last unit of work of the
 *                        compensation run)
 * @param handoffFailures the exceptions thrown by the after-commit handoffs of the committed units of work; non-null,
 *                        no {@code null} element, non-empty only for {@code WAITING}, {@code COMPLETED},
 *                        {@code COMPENSATED} or {@code REQUIRES_INTERVENTION}; an unmodifiable defensive copy
 * @throws IllegalArgumentException if {@code sagaType}, {@code outcome} or {@code handoffFailures} is {@code null},
 *                                  if {@code handoffFailures} contains {@code null} or is non-empty for another
 *                                  outcome, or if {@code outcomeStep} or {@code failure} is present or absent
 *                                  inconsistently with {@code outcome}
 */
public record SagaHandlingResult(
    String sagaType,
    String sagaId,
    HandlingOutcome outcome,
    boolean started,
    String outcomeStep,
    SagaFailure failure,
    String unitOfWorkId,
    List<Throwable> handoffFailures
) {
    public SagaHandlingResult {
        if (sagaType == null) {
            throw new IllegalArgumentException("sagaType must not be null");
        }
        if (outcome == null) {
            throw new IllegalArgumentException("outcome must not be null");
        }
        requirePresentIff("outcomeStep", outcomeStep, HandlingOutcome.COMPLETED, outcome);
        requirePresentIff("failure", failure, HandlingOutcome.FAILED, outcome);
        handoffFailures = copyHandoffFailures(handoffFailures, outcome);
    }

    private static List<Throwable> copyHandoffFailures(List<Throwable> handoffFailures, HandlingOutcome outcome) {
        if (handoffFailures == null) {
            throw new IllegalArgumentException("handoffFailures must not be null");
        }
        for (Throwable failure : handoffFailures) {
            if (failure == null) {
                throw new IllegalArgumentException("handoffFailures must not contain null elements");
            }
        }
        boolean committed = outcome == HandlingOutcome.WAITING || outcome == HandlingOutcome.COMPLETED
            || outcome == HandlingOutcome.COMPENSATED || outcome == HandlingOutcome.REQUIRES_INTERVENTION;
        if (!committed && !handoffFailures.isEmpty()) {
            throw new IllegalArgumentException("handoffFailures must be empty unless outcome is WAITING, COMPLETED, "
                + "COMPENSATED or REQUIRES_INTERVENTION, but outcome is " + outcome);
        }
        return List.copyOf(handoffFailures);
    }

    private static void requirePresentIff(String name, Object value, HandlingOutcome impliedBy,
                                          HandlingOutcome outcome) {
        if ((outcome == impliedBy) != (value != null)) {
            throw new IllegalArgumentException(
                name + " must be non-null if and only if outcome is " + impliedBy + ", but outcome is " + outcome
                    + " and " + name + " is " + value);
        }
    }
}
