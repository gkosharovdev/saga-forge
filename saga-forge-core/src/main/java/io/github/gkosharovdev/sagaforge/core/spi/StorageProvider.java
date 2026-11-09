package io.github.gkosharovdev.sagaforge.core.spi;

import java.util.List;
import java.util.Optional;

/**
 * SPI for durable saga instance persistence.
 *
 * <p>Implementations are provided by storage modules ({@code saga-forge-storage-postgres},
 * {@code saga-forge-storage-mongodb}, {@code saga-forge-storage-mysql}). Each storage module
 * depends only on {@code saga-forge-core} and the respective database driver, following the
 * pluggable module boundary pattern.</p>
 *
 * <h3>Null-safety contracts</h3>
 * <ul>
 *   <li>{@link #save(SagaInstance, List)} — implementations must throw
 *       {@link IllegalArgumentException} if {@code instance} or {@code newEntries} is {@code null}, if
 *       {@code newEntries} contains {@code null}, or if an entry's {@code sagaId} differs from
 *       {@code instance.sagaId()}.</li>
 *   <li>{@link #save(SagaInstance)} — implementations must throw
 *       {@link IllegalArgumentException} if {@code instance} is {@code null}.</li>
 *   <li>{@link #findById(String)} — implementations must throw
 *       {@link IllegalArgumentException} if {@code sagaId} is {@code null}.</li>
 *   <li>{@link #findByIdAndLock(String)} — implementations must throw
 *       {@link IllegalArgumentException} if {@code sagaId} is {@code null}.</li>
 *   <li>{@link #findByAssociation(String, String)} — implementations must throw
 *       {@link IllegalArgumentException} if either argument is {@code null}.</li>
 *   <li>{@link #delete(String)} — implementations must throw
 *       {@link IllegalArgumentException} if {@code sagaId} is {@code null}.</li>
 * </ul>
 *
 * <h3>Engine writes</h3>
 * <p>The saga engine writes an instance three ways, each with the lifecycle entries that belong to it, through
 * {@link #save(SagaInstance, List)}:</p>
 * <ul>
 *   <li>the <em>creation write</em>: the new {@link SagaStatus#CREATED} instance with every step not run, and its
 *       {@link SagaLifecycleEventKind#CREATED} entry, in its own transaction before the first unit of work;</li>
 *   <li>the <em>unit-of-work write</em>: the instance as a committed unit of work left it, with one
 *       {@link SagaLifecycleEventKind#STEP_COMPLETED} entry per completed step, followed by
 *       {@link SagaLifecycleEventKind#COMPLETED} if an {@code @EndSaga} step ended it, in the unit of work's
 *       transaction;</li>
 *   <li>the <em>{@code FAILED} write</em>: the instance marked {@link SagaStatus#FAILED} and its
 *       {@link SagaLifecycleEventKind#FAILED} entry, in its own transaction after the unit of work rolled back.</li>
 * </ul>
 * <p>The provider records exactly the entries it is given, in the order given, and derives none.</p>
 *
 * <h3>Transactions</h3>
 * <p>A provider built with the host's {@link TransactionBoundary} joins the transaction active on the calling thread
 * for every operation (reads included) by asking it for its resource by type, and never commits, rolls back or
 * closes that resource. With no transaction active, or built without a boundary, every operation runs in its own
 * transaction. Storage failures propagate as unchecked exceptions; a write that fails has written nothing.</p>
 */
public interface StorageProvider {

    /**
     * Persists the given saga instance and appends lifecycle entries to its history, in one transaction (the
     * joined one, or its own).
     *
     * <p>Creates or updates the instance record and its associations, and appends {@code newEntries} after every
     * entry already recorded for the instance, in list order. The provider assigns each appended entry its position
     * in the history; it derives nothing and records exactly the entries given.</p>
     *
     * @param instance   the saga instance to persist; must not be {@code null}
     * @param newEntries the lifecycle entries produced with this write, in order; must not be {@code null}, contain
     *                   {@code null}, or hold an entry whose {@code sagaId} differs from {@code instance.sagaId()};
     *                   may be empty
     * @throws IllegalArgumentException          if a pre-condition is violated; nothing is written
     * @throws SagaConcurrencyConflictException if the write detects that the stored instance changed
     *                                           concurrently; nothing is written
     */
    void save(SagaInstance instance, List<SagaLifecycleEntry> newEntries);

    /**
     * Persists the given saga instance, creating or updating as appropriate, and records no lifecycle history.
     * Equivalent to {@code save(instance, List.of())}.
     *
     * @param instance the saga instance to persist; must not be {@code null}
     * @throws IllegalArgumentException          if {@code instance} is {@code null}
     * @throws SagaConcurrencyConflictException if the write detects that the stored instance changed
     *                                           concurrently; nothing is written
     */
    default void save(SagaInstance instance) {
        save(instance, List.of());
    }

    /**
     * Retrieves a saga instance by its unique identifier.
     *
     * @param sagaId the saga instance identifier; must not be {@code null}
     * @return an {@link Optional} containing the saga instance, or empty if not found
     * @throws IllegalArgumentException if {@code sagaId} is {@code null}
     */
    Optional<SagaInstance> findById(String sagaId);

    /**
     * Retrieves a saga instance and holds an exclusive lock on it until the joined transaction commits or rolls
     * back. The engine calls it as the first statement of every unit of work's transaction.
     *
     * <p>The same call for the same {@code sagaId} in any other transaction, in this or another process, blocks
     * until then and then reads the committed state. Locks on different {@code sagaId}s are independent. A missing
     * instance returns empty and locks nothing. The lock must not block the {@link SagaContextStore}'s own writes
     * for the same {@code sagaId}.</p>
     *
     * @param sagaId the saga instance identifier; must not be {@code null}
     * @return an {@link Optional} containing the locked saga instance, or empty if not found
     * @throws IllegalArgumentException if {@code sagaId} is {@code null}
     * @throws IllegalStateException    if no transaction the provider has joined is active on the calling thread;
     *                                  thrown before the store is touched
     * @throws RuntimeException         if the store aborts the lock wait (timeout, deadlock victim)
     */
    Optional<SagaInstance> findByIdAndLock(String sagaId);

    /**
     * Finds all saga instances associated with the given property and value.
     *
     * @param associationProperty the association property name; must not be {@code null}
     * @param associationValue    the association property value; must not be {@code null}
     * @return a list of matching saga instances, never {@code null} (empty if none match)
     * @throws IllegalArgumentException if either argument is {@code null}
     */
    List<SagaInstance> findByAssociation(String associationProperty, String associationValue);

    /**
     * Deletes a saga instance by its unique identifier.
     *
     * @param sagaId the saga instance identifier; must not be {@code null}
     * @throws IllegalArgumentException if {@code sagaId} is {@code null}
     */
    void delete(String sagaId);
}
