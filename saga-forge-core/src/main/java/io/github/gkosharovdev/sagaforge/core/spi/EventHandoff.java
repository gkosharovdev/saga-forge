package io.github.gkosharovdev.sagaforge.core.spi;

/**
 * SPI receiving the domain events a committed unit of work buffered, one {@link HandedOffEvents} batch per unit
 * of work. The default implementation ({@code DispatchingEventHandoff} in {@code saga-forge-engine}) calls
 * {@link EventDispatcher#dispatch(Object)} for each event after commit; a transactional outbox can take its place
 * without an engine change.
 *
 * <p><strong>Implemented by the engine (default), a later outbox, or tests; never by client code.</strong></p>
 *
 * <h3>Which events</h3>
 * <p>Every event recorded in the unit of work (through {@code DomainEvents.record} or {@code EventPublisher} on its
 * thread) that no step of the owning instance ran in-process: events of other saga types or instances, events
 * triggering a {@code @StartSaga} step, events no step reacts to, and events still queued when an {@code @EndSaga}
 * step ended the unit of work. For a non-saga unit of work, every recorded event. Each event exactly once. Never for
 * a unit of work that rolled back or lost a write-time conflict; an empty batch is not handed off.</p>
 *
 * <h3>Order and identity</h3>
 * <p>Events are in recording order. {@link HandedOffEvents#unitOfWorkId()} is unique per unit of work, saga-scoped
 * or not; together with an event's index in {@link HandedOffEvents#events()} it identifies the event uniquely,
 * which an outbox can use as a key. Saga type and id are present for a saga-scoped unit of work and absent for a
 * non-saga one.</p>
 *
 * <h3>Timing</h3>
 * <ul>
 *   <li>{@link Timing#AFTER_COMMIT} (default): {@link #handOff(HandedOffEvents)} is called after the commit
 *       returned, after the instance lock was released, on the unit-of-work thread, with no engine transaction
 *       active, so it may call {@link EventReceiver#receive(Object)} (a synchronous transport looping events
 *       back). A thrown {@link RuntimeException} leaves the committed unit of work and instance untouched (not
 *       {@code FAILED}); it is listed in {@link SagaHandlingResult#handoffFailures()} (for a non-saga unit of work
 *       only logged) and logged.</li>
 *   <li>{@link Timing#IN_TRANSACTION}: called after the instance write and before the commit, inside the unit of
 *       work's transaction ({@link TransactionBoundary#current()} is that transaction), so whatever it writes
 *       through it commits or rolls back with the unit of work. It must not call
 *       {@link EventReceiver#receive(Object)} ({@link IllegalStateException}). A thrown {@link RuntimeException}
 *       fails the unit of work: rollback, then the {@code FAILED} write naming the step that ran last; for a
 *       non-saga unit of work, rollback and the exception rethrown from {@code CommandGateway.send}.</li>
 * </ul>
 *
 * <h3>Delivery guarantee and the outbox</h3>
 * <p>{@code AFTER_COMMIT} delivery is at most once: events of a committed unit of work are lost if the process
 * stops before or during the handoff, and nothing redelivers them. A transactional outbox closes that window with
 * an {@code IN_TRANSACTION} implementation that inserts one row per event keyed by {@code (unitOfWorkId, index)}
 * through {@code current().flatMap(tx -> tx.resource(Connection.class))}, and a relay that publishes committed rows
 * at least once.</p>
 *
 * <p>{@code EventPublisher.publish} outside any unit of work is not a handoff: it calls
 * {@link EventDispatcher#dispatch(Object)} immediately.</p>
 */
public interface EventHandoff {

    /** When the engine calls {@link #handOff(HandedOffEvents)} relative to the unit of work's commit. */
    enum Timing {

        /** After the commit, with the lock released and no engine transaction active. */
        AFTER_COMMIT,

        /** Inside the unit of work's transaction, after the instance write and before the commit. */
        IN_TRANSACTION
    }

    /**
     * Returns when this handoff is called. Read once, when the engine starts.
     *
     * @return {@link Timing#AFTER_COMMIT} unless overridden
     */
    default Timing timing() {
        return Timing.AFTER_COMMIT;
    }

    /**
     * Receives the buffered events of one unit of work.
     *
     * @param batch the unit of work's events and identity; never {@code null}, never empty
     */
    void handOff(HandedOffEvents batch);
}
