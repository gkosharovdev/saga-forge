package io.github.gkosharovdev.sagaforge.core.spi;

/**
 * Kind of transition a {@link SagaLifecycleEntry} records. Storage-agnostic: a storage module derives entries by
 * diffing the previously persisted {@link SagaInstance} snapshot against the incoming one.
 */
public enum SagaLifecycleEventKind {

    /** The instance was first persisted (no prior snapshot existed), whatever its first status. */
    CREATED,

    /** A step went from {@link StepStatus#NOT_RUN} to {@link StepStatus#COMPLETED}. */
    STEP_COMPLETED,

    /** The instance's status became {@link SagaStatus#COMPLETED}. */
    COMPLETED,

    /** The instance's status became {@link SagaStatus#FAILED}. */
    FAILED
}
