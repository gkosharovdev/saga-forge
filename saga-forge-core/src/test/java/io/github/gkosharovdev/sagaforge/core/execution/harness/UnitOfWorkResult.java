package io.github.gkosharovdev.sagaforge.core.execution.harness;

import io.github.gkosharovdev.sagaforge.core.spi.SagaFailure;
import java.util.List;

/**
 * What one delivered event did to one saga type: a unit of work that committed or rolled back, or an
 * unmatched, unexpected or ignored event (no unit of work ran).
 *
 * <p><strong>Test-only scaffolding.</strong> Lives in {@code src/test}; never move to {@code src/main}.</p>
 *
 * @param outcome    what happened; never {@code null}
 * @param event      the delivered event; never {@code null}
 * @param sagaType   the saga type, or {@code null} for {@link Outcome#IGNORED}
 * @param sagaId     the saga instance the unit of work ran for (or, for {@link Outcome#UNEXPECTED}, the
 *                   terminal instance the event correlated to); {@code null} for {@link Outcome#UNMATCHED},
 *                   {@link Outcome#IGNORED}, and a start that was itself rejected (for example a
 *                   {@code DuplicateSagaInstanceException})
 * @param stepsRun   names of the steps invoked in the unit of work, in invocation order, including a step
 *                   that failed; empty if no unit of work ran
 * @param failure    the {@link SagaFailure} written after a rollback, or {@code null}
 * @param cause      the exception that rolled the unit of work back, or {@code null}
 * @param dispatched events handed to {@code EventDispatcher.dispatch} after commit, in dispatch order; empty
 *                   unless {@link Outcome#COMMITTED}
 */
public record UnitOfWorkResult(
        Outcome outcome,
        Object event,
        Class<?> sagaType,
        String sagaId,
        List<String> stepsRun,
        SagaFailure failure,
        Throwable cause,
        List<Object> dispatched) {

    /** What a delivered event did to one saga type. */
    public enum Outcome {
        /** A unit of work ran and committed (at an {@code @EndSaga} step or a wait point). */
        COMMITTED,
        /** A unit of work ran and rolled back; the instance (if one was created) is {@code FAILED}. */
        ROLLED_BACK,
        /** The event triggers a non-start step but correlates to no active instance. */
        UNMATCHED,
        /** The event triggers a non-start step of an instance that is already terminal. */
        UNEXPECTED,
        /** The event triggers no step of any registered saga. */
        IGNORED
    }

    public UnitOfWorkResult {
        stepsRun = List.copyOf(stepsRun);
        dispatched = List.copyOf(dispatched);
    }

    /** {@code true} if a unit of work ran and committed. */
    public boolean committed() {
        return outcome == Outcome.COMMITTED;
    }

    /** {@code true} if a unit of work ran and rolled back. */
    public boolean rolledBack() {
        return outcome == Outcome.ROLLED_BACK;
    }

    static UnitOfWorkResult noUnitOfWork(Outcome outcome, Object event, Class<?> sagaType, String sagaId) {
        return new UnitOfWorkResult(outcome, event, sagaType, sagaId, List.of(), null, null, List.of());
    }
}
