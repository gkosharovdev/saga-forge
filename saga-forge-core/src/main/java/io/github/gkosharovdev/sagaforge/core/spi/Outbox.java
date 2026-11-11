package io.github.gkosharovdev.sagaforge.core.spi;

/**
 * SPI receiving the outgoing messages of a committed unit of work, one {@link OutgoingMessages} batch per unit
 * of work: its buffered domain events and, since {@code specs/007-in-memory-transport}, its
 * {@code @Command(delivery = OUTBOX)} commands. Named {@code EventHandoff} (with {@code handOff}) before 007. The default implementation ({@code ImmediateDispatchOutbox} in {@code saga-forge-engine}) calls
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
 * a unit of work that rolled back or lost a write-time conflict; an empty batch is not stored in the outbox.</p>
 *
 * <h3>Order and identity</h3>
 * <p>Events are in recording order. {@link OutgoingMessages#unitOfWorkId()} is unique per unit of work, saga-scoped
 * or not; together with an event's index in {@link OutgoingMessages#events()} it identifies the event uniquely,
 * which an outbox can use as a key. Saga type and id are present for a saga-scoped unit of work and absent for a
 * non-saga one.</p>
 *
 * <h3>Timing</h3>
 * <ul>
 *   <li>{@link Timing#AFTER_COMMIT} (default): {@link #store(OutgoingMessages)} is called after the commit
 *       returned, after the instance lock was released, on the unit-of-work thread, with no engine transaction
 *       active, so it may call {@link EventReceiver#receive(Object)} (a synchronous transport looping events
 *       back). A thrown {@link RuntimeException} leaves the committed unit of work and instance untouched (not
 *       {@code FAILED}); it is listed in {@link SagaHandlingResult#outboxFailures()} (for a non-saga unit of work
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
 * stops before or during the outbox, and nothing redelivers them. A transactional outbox closes that window with
 * an {@code IN_TRANSACTION} implementation that inserts one row per event keyed by {@code (unitOfWorkId, index)}
 * through {@code current().flatMap(tx -> tx.resource(Connection.class))}, and a relay that publishes committed rows
 * at least once.</p>
 *
 * <p>{@code EventPublisher.publish} outside any unit of work is not a outbox: it calls
 * {@link EventDispatcher#dispatch(Object)} immediately.</p>
 */
public interface Outbox {

    /** When the engine calls {@link #store(OutgoingMessages)} relative to the unit of work's commit. */
    enum Timing {

        /** After the commit, with the lock released and no engine transaction active. */
        AFTER_COMMIT,

        /** Inside the unit of work's transaction, after the instance write and before the commit. */
        IN_TRANSACTION
    }

    /**
     * Returns when this outbox is called. Read once, when the engine starts.
     *
     * @return {@link Timing#AFTER_COMMIT} unless overridden
     */
    default Timing timing() {
        return Timing.AFTER_COMMIT;
    }

    /**
     * Receives the outgoing messages of one unit of work.
     *
     * @param batch the unit of work's events, outbox commands and identity; never {@code null}, never empty
     */
    void store(OutgoingMessages batch);

    /**
     * Reports whether this outbox can store {@code @Command(delivery = OUTBOX)} commands. The engine refuses to start
     * with a model that sends outbox commands to an outbox that cannot ({@code specs/007-in-memory-transport}).
     *
     * @return {@code false} unless overridden
     */
    default boolean supportsCommands() {
        return false;
    }

    /**
     * Called after the commit of a unit of work whose batch this outbox stored with {@link Timing#IN_TRANSACTION},
     * after the instance lock was released, on the unit-of-work thread, with no engine transaction active; for
     * example to wake a relay ({@code specs/007-in-memory-transport} research §2). Never called after a rollback,
     * and never for {@link Timing#AFTER_COMMIT} outboxes, whose {@link #store(OutgoingMessages)} already runs after
     * the commit. A thrown {@link RuntimeException} is logged and changes nothing.
     *
     * @param batch the batch this outbox stored in the committed transaction; never {@code null}
     */
    default void committed(OutgoingMessages batch) {
    }
}
