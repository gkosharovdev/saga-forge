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

    /** The event triggers a non-start step, but no instance of the saga type correlates. Nothing changed. */
    UNMATCHED,

    /**
     * The event triggers a non-start step, but only terminal instances correlate, or the locked instance turned
     * terminal. Nothing changed.
     */
    UNEXPECTED,

    /**
     * The event was not handled for this instance and the transport may redeliver it: either a unit of work ran
     * and lost a write-time conflict ({@link SagaConcurrencyConflictException}; rolled back, no {@code FAILED}
     * write), or the instance is still {@link SagaStatus#CREATED} because its start unit of work has not committed
     * (no unit of work ran). Nothing changed.
     */
    NOT_HANDLED
}
