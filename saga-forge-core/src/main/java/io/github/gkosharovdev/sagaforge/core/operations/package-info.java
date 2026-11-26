/**
 * Saga operations: finding saga instances, repairing the ones that need a person's decision, and
 * recovering stranded and stalled ones (specs/015-saga-operations).
 *
 * <p>{@link io.github.gkosharovdev.sagaforge.core.operations.SagaOperations} is the host-facing
 * contract; the runtime implements it and hands it out from the running runtime. Queries read
 * committed state and never lock an instance. Each repair runs through the runtime's unit of work,
 * is admitted against the instance's status read under the instance lock, is recorded in the
 * instance's lifecycle history with the operator and the reason, and reports a {@link
 * io.github.gkosharovdev.sagaforge.core.operations.RepairResult} instead of throwing when the
 * instance is in the wrong status.
 *
 * <p>An instance view is the {@link io.github.gkosharovdev.sagaforge.core.spi.SagaInstance} itself;
 * {@code SagaContext} values are never part of it and are read on their own with {@link
 * io.github.gkosharovdev.sagaforge.core.operations.SagaOperations#context(String)}.
 */
package io.github.gkosharovdev.sagaforge.core.operations;
