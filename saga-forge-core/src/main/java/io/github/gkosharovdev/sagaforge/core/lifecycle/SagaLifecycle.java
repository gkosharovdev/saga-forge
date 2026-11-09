package io.github.gkosharovdev.sagaforge.core.lifecycle;

import java.util.List;

import io.github.gkosharovdev.sagaforge.core.spi.AggregateReference;
import io.github.gkosharovdev.sagaforge.core.spi.SagaFailure;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.core.spi.StepRecord;
import io.github.gkosharovdev.sagaforge.core.spi.StepStatus;

/**
 * Process-orchestration contract governing the whole-instance transitions a saga instance goes through:
 * creation, per-step completion records, advancement at wait points, completion, and failure (FR-005).
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
 * start                                   --&gt; CREATED   (every step NOT_RUN)
 * CREATED / RUNNING --advance (wait point)--&gt; RUNNING
 * CREATED / RUNNING --complete(@EndSaga)---&gt; COMPLETED (outcome recorded; terminal)
 * CREATED / RUNNING --fail----------------&gt; FAILED    (failure recorded; terminal)
 * </pre>
 * <ul>
 *   <li>{@link #recordStepCompleted(String, String, List)} changes a single step's
 *       {@link StepRecord} and never changes the instance's status.</li>
 *   <li>Terminal statuses for this contract are {@link SagaStatus#COMPLETED} and
 *       {@link SagaStatus#FAILED}. Once an instance is terminal, every further
 *       {@link #recordStepCompleted(String, String, List)}, {@link #advance(String)},
 *       {@link #complete(String, String)} or {@link #fail(String, SagaFailure)} call for its saga id is
 *       rejected with {@link InvalidSagaTransitionException}, and it no longer counts as an active instance
 *       for {@link #start(Class, String, String)}'s duplicate check.</li>
 *   <li>{@link SagaStatus#FAILED} is entered only through {@link #fail(String, SagaFailure)}.</li>
 *   <li>This contract never moves an instance into {@link SagaStatus#COMPENSATING}; it is a
 *       forward-compatibility placeholder for the not-yet-built compensation-dispatch feature.</li>
 * </ul>
 *
 * <h3>Relationship to other contracts</h3>
 * <ul>
 *   <li>The engine resolves which instance an incoming event correlates to, through
 *       {@code StorageProvider.findByAssociation} behind {@code EventReceiver}; having resolved a target,
 *       it calls into {@code SagaLifecycle} to perform the transition. {@code EventDispatcher} is outbound
 *       only and never calls {@code SagaLifecycle}.</li>
 *   <li>{@code StorageProvider} is not called by this contract's methods as specified here: the
 *       contract only defines the transition semantics and the returned {@link SagaInstance} snapshot.
 *       The engine persists the returned snapshots with its three writes through
 *       {@code StorageProvider.save(instance, entries)}: the creation write, the unit of work's write and
 *       the {@code FAILED} write.</li>
 * </ul>
 *
 * <h3>Null-safety contracts</h3>
 * <p>Implementations must throw {@link IllegalArgumentException} if any argument is {@code null}:</p>
 * <ul>
 *   <li>{@link #start(Class, String, String)} — {@code sagaType}, {@code associationProperty},
 *       {@code associationValue}.</li>
 *   <li>{@link #recordStepCompleted(String, String, List)} — {@code sagaId}, {@code stepName},
 *       {@code touchedAggregates}.</li>
 *   <li>{@link #advance(String)} — {@code sagaId}.</li>
 *   <li>{@link #complete(String, String)} — {@code sagaId}, {@code outcomeStep}.</li>
 *   <li>{@link #fail(String, SagaFailure)} — {@code sagaId}, {@code failure}.</li>
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
     * {@code status = }{@link SagaStatus#CREATED}, empty {@code state}, an {@code associations}
     * entry for {@code associationProperty -> associationValue}, {@code steps} holding every
     * {@code @SagaStep} method name of {@code sagaType} mapped to {@link StepRecord#notRun()}, and
     * {@code outcome} and {@code failure} both {@code null}.</p>
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
     * Records that the step {@code stepName} of the saga instance identified by {@code sagaId} completed,
     * touching {@code touchedAggregates}.
     *
     * <p>Pre-condition: an instance with {@code sagaId} exists, is not terminal, and {@code stepName} is
     * one of its {@code steps}.</p>
     *
     * <p>Effect: returns an updated {@link SagaInstance} whose {@code steps[stepName]} is
     * {@link StepStatus#COMPLETED} with {@code touchedAggregates} (deduplicated, in recording order, as
     * {@link StepRecord#completed(List)} does). The instance's status is unchanged.</p>
     *
     * @param sagaId            the saga instance identifier; must not be {@code null}
     * @param stepName          the {@code @SagaStep} method name that completed; must not be {@code null}
     * @param touchedAggregates aggregates touched by the step, in recording order; must not be
     *                          {@code null} or contain {@code null} elements
     * @return the updated saga instance, with an unchanged status
     * @throws InvalidSagaTransitionException if no instance exists for {@code sagaId}
     *                                        ({@link InvalidSagaTransitionException#notFound}) or it is
     *                                        already in a terminal status
     *                                        ({@link InvalidSagaTransitionException#terminal}); the
     *                                        operation is reported as {@code "recordStepCompleted"}
     * @throws IllegalArgumentException       if any argument is {@code null}, or if {@code stepName} is
     *                                        not one of the instance's steps
     */
    SagaInstance recordStepCompleted(String sagaId, String stepName, List<AggregateReference> touchedAggregates);

    /**
     * Records that a unit of work for the saga instance identified by {@code sagaId} committed at a wait
     * point, that is, without reaching an {@code @EndSaga} step.
     *
     * <p>Pre-condition: an instance with {@code sagaId} exists and its status is neither
     * {@link SagaStatus#COMPLETED} nor {@link SagaStatus#FAILED}.</p>
     *
     * <p>Effect: returns an updated {@link SagaInstance} in {@link SagaStatus#RUNNING}, reached from either
     * {@link SagaStatus#CREATED} or {@link SagaStatus#RUNNING}. {@code steps} are unchanged.</p>
     *
     * @param sagaId the saga instance identifier; must not be {@code null}
     * @return the updated saga instance, in status {@link SagaStatus#RUNNING}
     * @throws InvalidSagaTransitionException if no instance exists for {@code sagaId}
     *                                        ({@link InvalidSagaTransitionException#notFound}) or it is
     *                                        already in a terminal status
     *                                        ({@link InvalidSagaTransitionException#terminal}) (FR-006)
     * @throws IllegalArgumentException       if {@code sagaId} is {@code null}
     */
    SagaInstance advance(String sagaId);

    /**
     * Marks the saga instance identified by {@code sagaId} complete, recording {@code outcomeStep} as the
     * {@code @EndSaga} step it reached.
     *
     * <p>Pre-condition: an instance with {@code sagaId} exists, is not already terminal, and
     * {@code outcomeStep} is an {@code @EndSaga} step of the saga. {@code SagaLifecycle} holds no saga
     * metadata, so verifying the {@code @EndSaga} part of this pre-condition is the caller's (the
     * engine's) responsibility; this contract only guarantees the status transition and the
     * terminal-status rejection below.</p>
     *
     * <p>Effect: returns an updated {@link SagaInstance} with
     * {@code status = }{@link SagaStatus#COMPLETED} and {@code outcome = outcomeStep}, reached from either
     * {@link SagaStatus#RUNNING} or {@link SagaStatus#CREATED} (a saga that finishes in its first unit of
     * work). {@code steps} are unchanged. No further transition for this {@code sagaId} succeeds
     * afterward.</p>
     *
     * @param sagaId      the saga instance identifier; must not be {@code null}
     * @param outcomeStep the name of the reached {@code @EndSaga} step; must not be {@code null}
     * @return the updated saga instance, in status {@link SagaStatus#COMPLETED}
     * @throws InvalidSagaTransitionException if no instance exists for {@code sagaId}
     *                                        ({@link InvalidSagaTransitionException#notFound}) or it is
     *                                        already in a terminal status
     *                                        ({@link InvalidSagaTransitionException#terminal}) (FR-006)
     * @throws IllegalArgumentException       if any argument is {@code null}
     */
    SagaInstance complete(String sagaId, String outcomeStep);

    /**
     * Marks the saga instance identified by {@code sagaId} failed, recording {@code failure}.
     *
     * <p>Pre-condition: an instance with {@code sagaId} exists and is not already terminal.</p>
     *
     * <p>Effect: returns an updated {@link SagaInstance} with
     * {@code status = }{@link SagaStatus#FAILED} and {@code failure} recorded, reached from either
     * {@link SagaStatus#CREATED} or {@link SagaStatus#RUNNING}. {@code steps} are unchanged. No further
     * transition for this {@code sagaId} succeeds afterward.</p>
     *
     * @param sagaId  the saga instance identifier; must not be {@code null}
     * @param failure the failure that moved the instance to {@code FAILED}; must not be {@code null}
     * @return the updated saga instance, in status {@link SagaStatus#FAILED}
     * @throws InvalidSagaTransitionException if no instance exists for {@code sagaId}
     *                                        ({@link InvalidSagaTransitionException#notFound}) or it is
     *                                        already in a terminal status
     *                                        ({@link InvalidSagaTransitionException#terminal}) (FR-006)
     * @throws IllegalArgumentException       if any argument is {@code null}
     */
    SagaInstance fail(String sagaId, SagaFailure failure);
}
