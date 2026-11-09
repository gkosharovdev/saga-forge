package io.github.gkosharovdev.sagaforge.core.spi;

/**
 * What handling one event did to one saga type or instance; the {@link SagaHandlingResult#outcome()} reported by
 * {@link EventReceiver#receive(Object)}.
 */
public enum HandlingOutcome {

    /**
     * A unit of work ran and committed at a wait point: no queued event was left for the instance, which is now
     * {@link SagaStatus#RUNNING}.
     */
    WAITING,

    /** A unit of work ran and committed at an {@code @EndSaga} step; the instance is {@link SagaStatus#COMPLETED}. */
    COMPLETED,

    /**
     * A unit of work ran and rolled back; the instance was then written {@link SagaStatus#FAILED} in its own
     * write.
     */
    FAILED,

    /**
     * A start event arrived while an active instance of the saga type has the same association value. No unit of
     * work ran; nothing changed.
     */
    DUPLICATE_START,

    /**
     * The event triggers a step that is already {@link StepStatus#COMPLETED} for the instance, whatever the
     * instance's status except {@link SagaStatus#COMPENSATING} (which resumes compensation instead): a redelivered or
     * repeated event ({@code specs/005-step-retry} FR-006). No unit of work ran; nothing changed.
     */
    DUPLICATE,

    /** The event triggers a non-start step, but no instance of the saga type correlates. Nothing changed. */
    UNMATCHED,

    /**
     * The event triggers a non-start step, but only terminal instances correlate, or the locked instance turned
     * terminal, and the step has not completed for it ({@link #DUPLICATE} otherwise). Nothing changed.
     */
    UNEXPECTED,

    /**
     * The event was not handled for this instance and the transport may redeliver it: either a unit of work ran
     * and lost a write-time conflict ({@link SagaConcurrencyConflictException}; rolled back, no {@code FAILED}
     * write), or the instance is still {@link SagaStatus#CREATED} because its start unit of work has not committed
     * (no unit of work ran), or the engine was interrupted while waiting to retry the unit of work (the claimed
     * attempt stays recorded). Nothing changed.
     */
    NOT_HANDLED,

    /**
     * The instance compensated: every applicable {@code @SagaCompensation} committed, each in its own unit of work,
     * and the instance is {@link SagaStatus#COMPENSATED} ({@code specs/006-saga-compensation} FR-015). Reported by the
     * handling call that triggered compensation, or by one that resumed it.
     */
    COMPENSATED,

    /**
     * A compensation failed: its unit of work rolled back, unwinding stopped, and the instance is
     * {@link SagaStatus#REQUIRES_INTERVENTION} ({@code specs/006-saga-compensation} FR-016). Never retried.
     */
    REQUIRES_INTERVENTION
}
