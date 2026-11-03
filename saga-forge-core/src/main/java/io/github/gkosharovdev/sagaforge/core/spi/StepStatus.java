package io.github.gkosharovdev.sagaforge.core.spi;

/**
 * Persisted completion status of a single {@code @SagaStep} method within a saga instance.
 */
public enum StepStatus {

    /**
     * The step has not run, or it ran inside a unit of work that rolled back. A step whose unit of work
     * rolled back stays {@code NOT_RUN}.
     */
    NOT_RUN,

    /**
     * The step method returned, every command it dispatched was handled, and its unit of work committed.
     */
    COMPLETED
}
