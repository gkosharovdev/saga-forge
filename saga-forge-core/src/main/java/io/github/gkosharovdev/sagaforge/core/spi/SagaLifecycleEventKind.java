package io.github.gkosharovdev.sagaforge.core.spi;

/**
 * Kind of transition a {@link SagaLifecycleEntry} records. Storage-agnostic: the engine produces one entry per
 * transition and hands it to storage with the instance write it belongs to. There is no kind for a change to
 * {@link SagaStatus#RUNNING} (a wait point records no status entry) or to {@link SagaStatus#COMPENSATING}.
 */
public enum SagaLifecycleEventKind {
    /** The creation write: the instance was first persisted, {@link SagaStatus#CREATED} with no step run. */
    CREATED,
    /** One step completed within a committed unit of work; handed over with that unit of work's write. */
    STEP_COMPLETED,
    /** An {@code @EndSaga} step ended the instance, now {@link SagaStatus#COMPLETED}, with that step as outcome. */
    COMPLETED,
    /** The {@code FAILED} write after a rolled-back unit of work: the instance is now {@link SagaStatus#FAILED}. */
    FAILED
}
