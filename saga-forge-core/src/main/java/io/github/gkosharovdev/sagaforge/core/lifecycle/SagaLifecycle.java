package io.github.gkosharovdev.sagaforge.core.lifecycle;

import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;

/**
 * Process-orchestration contract governing the whole-instance transitions a saga instance goes through:
 * creation, step advancement, and completion (FR-005).
 *
 * <p>Consumed by the saga engine/runtime only, never by step-author code inside a {@code @SagaStep}
 * method body (FR-008). This contract is distinct from {@link SagaContext}, which governs per-step
 * business state and is reached by step-author code (FR-007): {@code SagaLifecycle} answers "which
 * lifecycle transition am I causing", {@code SagaContext} answers "which business state am I reading or
 * writing".</p>
 *
 * <p>No production implementation ships in {@code saga-forge-core}; implementations are provided by the
 * engine runtime, the same posture as {@code StorageProvider} and {@code EventDispatcher}.</p>
 *
 * <h3>Status transitions</h3>
 * <p>Transitions are expressed purely in terms of the existing {@link SagaStatus} enum:</p>
 * <pre>
 * CREATED --advance--&gt; RUNNING --advance--&gt; RUNNING --complete--&gt; COMPLETED (terminal)
 * CREATED --complete (single-step saga)------------------------&gt; COMPLETED (terminal)
 * </pre>
 * <ul>
 *   <li>Terminal statuses for this contract are {@link SagaStatus#COMPLETED} and
 *       {@link SagaStatus#FAILED}. Once an instance is terminal, every further {@link #advance(String)}
 *       or {@link #complete(String)} call for its saga id is rejected with
 *       {@link InvalidSagaTransitionException}, and it no longer counts as an active instance for
 *       {@link #start(Class, String, String)}'s duplicate check.</li>
 *   <li>This contract never moves an instance into {@link SagaStatus#FAILED} or
 *       {@link SagaStatus#COMPENSATING}. {@code FAILED} is reserved for the not-yet-built
 *       failure-categorization feature; {@code COMPENSATING} is a forward-compatibility placeholder for
 *       the not-yet-built compensation-dispatch feature and is not driven by this contract.</li>
 * </ul>
 *
 * <h3>Relationship to other contracts</h3>
 * <ul>
 *   <li>{@code EventDispatcher} resolves which instance an incoming event correlates to; the engine,
 *       having resolved a target, calls into {@code SagaLifecycle} to perform the transition.
 *       {@code EventDispatcher} itself never calls {@code SagaLifecycle}.</li>
 *   <li>{@code StorageProvider} is not called by this contract's methods as specified here: the
 *       contract only defines the transition semantics and the returned {@link SagaInstance} snapshot.
 *       The engine persists that snapshot via {@code StorageProvider.save(...)}; engine-side
 *       implementations may call {@code StorageProvider} as part of fulfilling these operations
 *       durably.</li>
 * </ul>
 *
 * <h3>Null-safety contracts</h3>
 * <ul>
 *   <li>{@link #start(Class, String, String)} — implementations must throw
 *       {@link IllegalArgumentException} if any argument is {@code null}.</li>
 *   <li>{@link #advance(String)} — implementations must throw
 *       {@link IllegalArgumentException} if {@code sagaId} is {@code null}.</li>
 *   <li>{@link #complete(String)} — implementations must throw
 *       {@link IllegalArgumentException} if {@code sagaId} is {@code null}.</li>
 * </ul>
 */
public interface SagaLifecycle {

    /**
     * Creates a new saga instance of {@code sagaType} correlated to {@code associationValue} under
     * {@code associationProperty}.
     *
     * <p>Pre-condition: no currently active (non-terminal) instance of {@code sagaType} already
     * correlates to {@code associationValue} under {@code associationProperty}.</p>
     *
     * <p>Effect: returns a new {@link SagaInstance} with a freshly generated {@code sagaId},
     * {@code status = }{@link SagaStatus#CREATED}, empty {@code state}, and an {@code associations}
     * entry for {@code associationProperty -> associationValue}.</p>
     *
     * @param sagaType            the {@code @Saga} class to start an instance of; must not be
     *                            {@code null}
     * @param associationProperty the association property used to correlate the instance; must not be
     *                            {@code null}
     * @param associationValue    the association value the instance correlates to; must not be
     *                            {@code null}
     * @return the newly created saga instance, in status {@link SagaStatus#CREATED}
     * @throws DuplicateSagaInstanceException if an active (non-terminal) instance of {@code sagaType}
     *                                        already correlates to the same association
     *                                        property/value pair (FR-006)
     * @throws IllegalArgumentException       if any argument is {@code null}
     */
    SagaInstance start(Class<?> sagaType, String associationProperty, String associationValue);

    /**
     * Advances the saga instance identified by {@code sagaId} to its next step.
     *
     * <p>Pre-condition: an instance with {@code sagaId} exists and its status is neither
     * {@link SagaStatus#COMPLETED} nor {@link SagaStatus#FAILED}.</p>
     *
     * <p>Effect: returns an updated {@link SagaInstance} reflecting progress to the next step. A
     * {@link SagaStatus#CREATED} instance transitions to {@link SagaStatus#RUNNING} on its first
     * advancement; a {@link SagaStatus#RUNNING} instance remains {@code RUNNING}.</p>
     *
     * @param sagaId the saga instance identifier; must not be {@code null}
     * @return the updated saga instance
     * @throws InvalidSagaTransitionException if no instance exists for {@code sagaId}
     *                                        ({@link InvalidSagaTransitionException#notFound}) or it is
     *                                        already in a terminal status
     *                                        ({@link InvalidSagaTransitionException#terminal}) (FR-006)
     * @throws IllegalArgumentException       if {@code sagaId} is {@code null}
     */
    SagaInstance advance(String sagaId);

    /**
     * Marks the saga instance identified by {@code sagaId} complete.
     *
     * <p>Pre-condition: an instance with {@code sagaId} exists, is not already terminal, and its current
     * step is marked {@code @EndSaga}. {@link SagaInstance} carries no current-step field, so verifying
     * the {@code @EndSaga} part of this pre-condition is the caller's (the engine's) responsibility; this
     * contract only guarantees the status transition and the terminal-status rejection below.</p>
     *
     * <p>Effect: returns an updated {@link SagaInstance} with
     * {@code status = }{@link SagaStatus#COMPLETED}, reached from either {@link SagaStatus#RUNNING} or,
     * for a single-step saga, directly from {@link SagaStatus#CREATED}. No further {@link #advance(String)}
     * or {@code complete} call for this {@code sagaId} succeeds afterward.</p>
     *
     * @param sagaId the saga instance identifier; must not be {@code null}
     * @return the updated saga instance, in status {@link SagaStatus#COMPLETED}
     * @throws InvalidSagaTransitionException if no instance exists for {@code sagaId}
     *                                        ({@link InvalidSagaTransitionException#notFound}) or it is
     *                                        already in a terminal status
     *                                        ({@link InvalidSagaTransitionException#terminal}) (FR-006)
     * @throws IllegalArgumentException       if {@code sagaId} is {@code null}
     */
    SagaInstance complete(String sagaId);
}
