package io.github.gkosharovdev.sagaforge.core.spi;

import java.util.List;

/**
 * The result of handling one event for one saga type or instance, as reported in a {@link HandlingReport}.
 *
 * <p>The canonical constructor throws {@link IllegalArgumentException} if {@code sagaType}, {@code outcome} or {@code outboxFailures} is {@code null},
 *                                  if {@code outboxFailures} contains {@code null} or is non-empty for another
 *                                  outcome, or if {@code outcomeStep} or {@code failure} is present or absent
 *                                  inconsistently with {@code outcome}.</p>
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
 * @param outboxFailures the exceptions thrown by the after-commit outboxs of the committed units of work; non-null,
 *                        no {@code null} element, non-empty only for {@code WAITING}, {@code COMPLETED},
 *                        {@code COMPENSATED} or {@code REQUIRES_INTERVENTION}; an unmodifiable defensive copy
 */
public record SagaHandlingResult(
    String sagaType,
    String sagaId,
    HandlingOutcome outcome,
    boolean started,
    String outcomeStep,
    SagaFailure failure,
    String unitOfWorkId,
    List<Throwable> outboxFailures
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
        outboxFailures = copyOutboxFailures(outboxFailures, outcome);
    }

    private static List<Throwable> copyOutboxFailures(List<Throwable> outboxFailures, HandlingOutcome outcome) {
        if (outboxFailures == null) {
            throw new IllegalArgumentException("outboxFailures must not be null");
        }
        for (Throwable failure : outboxFailures) {
            if (failure == null) {
                throw new IllegalArgumentException("outboxFailures must not contain null elements");
            }
        }
        boolean committed = outcome == HandlingOutcome.WAITING || outcome == HandlingOutcome.COMPLETED
            || outcome == HandlingOutcome.COMPENSATED || outcome == HandlingOutcome.REQUIRES_INTERVENTION;
        if (!committed && !outboxFailures.isEmpty()) {
            throw new IllegalArgumentException("outboxFailures must be empty unless outcome is WAITING, COMPLETED, "
                + "COMPENSATED or REQUIRES_INTERVENTION, but outcome is " + outcome);
        }
        return List.copyOf(outboxFailures);
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
