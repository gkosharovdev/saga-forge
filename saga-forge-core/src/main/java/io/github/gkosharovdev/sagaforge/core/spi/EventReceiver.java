package io.github.gkosharovdev.sagaforge.core.spi;

/**
 * Inbound entry point of the saga engine: the counterpart of {@link EventDispatcher}. A transport hands every
 * domain event it delivers to {@link #receive(Object)}; routing and correlation to saga types and instances are the
 * engine's.
 *
 * <p><strong>Implemented by the engine</strong> ({@code SagaEngine} in {@code saga-forge-engine}); called by
 * transport modules and by tests standing in for them. Transports depend on {@code saga-forge-core} only.</p>
 *
 * <h3>Pre-conditions</h3>
 * <ul>
 *   <li>The engine has started successfully.</li>
 *   <li>No unit of work is running on the calling thread.</li>
 *   <li>{@code domainEvent} is non-null, its class is {@code @DomainEvent}-annotated, and every registered step
 *       reacting to its exact class can read a non-null, non-empty association value from it (record accessor,
 *       getter or field, read as {@code String.valueOf}).</li>
 * </ul>
 *
 * <h3>Effect</h3>
 * <p>Synchronously, on the calling thread, for each registered saga type in registration order with a
 * {@code @SagaStep} whose parameter type is the event's exact class: a {@code @StartSaga} step starts a new
 * instance correlated by that step's {@code associationProperty} value, subject to the duplicate-start rule, and
 * runs its first unit of work; any other step resolves the correlated instances of that saga type through
 * {@link StorageProvider#findByAssociation(String, String)} and runs one unit of work per active instance, in the
 * order returned. Each unit of work is committed or rolled back independently of the others.</p>
 *
 * <h3>Result</h3>
 * <p>One {@link SagaHandlingResult} per affected saga type and instance. {@link HandlingReport#ignored()} is
 * {@code true} if and only if no registered saga type reacts to the event; then nothing happened. A saga ending
 * {@link HandlingOutcome#FAILED} is a result, not an error.</p>
 *
 * <h3>Failures</h3>
 * <ul>
 *   <li>{@link IllegalArgumentException} for a {@code null} or non-{@code @DomainEvent} event, or a missing
 *       association value, before anything runs; the message names the event type and, for a missing value, the
 *       saga type, step and property.</li>
 *   <li>{@link IllegalStateException} if the engine has not started (never started, or its start was refused), or
 *       if a unit of work is running on this thread (a step, handler or in-transaction outbox calling back in);
 *       in the latter case the running unit of work fails like any other failure.</li>
 *   <li>{@link EventHandlingException} after every affected instance was attempted, if for at least one of them no
 *       outcome could be recorded (the store was unavailable, the creation write or the {@code FAILED} write
 *       failed, or a lock wait was aborted by the store). It carries the partial report; the transport should
 *       redeliver.</li>
 * </ul>
 *
 * <h3>Reentrancy and threading</h3>
 * <ul>
 *   <li>Called from inside an {@link Outbox.Timing#AFTER_COMMIT} outbox (a synchronous transport looping a
 *       dispatched event back): accepted. The previous unit of work has committed and released its locks, so this
 *       is a new unit of work.</li>
 *   <li>Called concurrently from several threads: units of work of the same instance serialize; different
 *       instances do not wait for each other.</li>
 *   <li>Called while the host has a transaction of its own active on the thread: the engine's
 *       {@link TransactionBoundary#begin()} suspends it or throws {@link IllegalStateException}, depending on the
 *       boundary.</li>
 * </ul>
 */
public interface EventReceiver {

    /**
     * Routes {@code domainEvent} to every saga type and instance that reacts to it and runs their units of work.
     *
     * @param domainEvent the domain event delivered by the transport; must not be {@code null}
     * @return the report of what happened, one result per affected saga type and instance; never {@code null}
     * @throws IllegalArgumentException if {@code domainEvent} is {@code null}, not {@code @DomainEvent}-annotated,
     *                                  or lacks an association value a reacting step needs
     * @throws IllegalStateException    if the engine has not started, or a unit of work is running on this thread
     * @throws EventHandlingException   if no outcome could be recorded for at least one affected instance
     */
    HandlingReport receive(Object domainEvent);
}
