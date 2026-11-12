package io.github.gkosharovdev.sagaforge.core.spi;

import java.util.Optional;

/**
 * SPI through which the engine begins the host's local transaction, and through which storage providers and an
 * outbox outbox find the transaction that is active on the calling thread.
 *
 * <p><strong>Implemented by host integrations, never by client code.</strong> The Spring Boot starter will map it
 * onto Spring's transaction manager; {@code saga-forge-storage-postgres} ships a JDBC-only implementation for hosts
 * without Spring or JTA; tests use in-memory doubles. Core depends on neither JTA nor Spring: the mapping below is
 * documentation only.</p>
 *
 * <h2>Thread binding</h2>
 * <p>A transaction is bound to the thread that began it. {@link #current()} returns the transaction begun through
 * this boundary that is active on the calling thread, until it is committed or rolled back.</p>
 *
 * <h2>Never joins</h2>
 * <p>{@link #begin()} always starts a new top-level local transaction. If one is already active on the thread, an
 * implementation either suspends it until the new one ends, or throws {@link IllegalStateException}; which one it
 * does is part of its documentation. There is no nesting, no savepoint and no two-phase commit.</p>
 *
 * <h2>How the engine uses it</h2>
 * <p>The engine begins one transaction for the creation write of a new instance, one per saga-scoped unit of work
 * (from before {@link StorageProvider#findByIdAndLock(String)} to after the instance write), one per non-saga unit
 * of work opened by {@code CommandGateway.send} outside any unit of work, and one for the {@code FAILED} write after
 * a rollback. When a handler fails inside {@code CommandGateway.send} it calls
 * {@link LocalTransaction#setRollbackOnly()}, and it checks {@link LocalTransaction#isRollbackOnly()} before
 * committing.</p>
 *
 * <h2>Joining</h2>
 * <ul>
 *   <li><strong>Storage providers</strong> built with the host's boundary use
 *       {@code current().flatMap(tx -> tx.resource(T.class))} for every call while a transaction is active (reads
 *       included), and their own transaction otherwise. They never commit, roll back, reconfigure or close the
 *       joined resource. A call inside an active transaction that has no resource of the expected type fails with
 *       {@link IllegalStateException} naming the type.</li>
 *   <li><strong>Aggregate access</strong> joins by running on the unit-of-work thread while the transaction is
 *       active: host-managed repositories join through the host's own binding; plain-JDBC client code reads
 *       {@code resource(java.sql.Connection.class)}.</li>
 *   <li><strong>{@link SagaContextStore}</strong> is never built with the boundary and never joins, so context
 *       values survive a rollback.</li>
 *   <li><strong>An outbox outbox</strong> ({@link Outbox.Timing#IN_TRANSACTION}) writes through
 *       {@link #current()} the same way, so its rows commit or roll back with the unit of work.</li>
 * </ul>
 *
 * <h2>Mapping</h2>
 * <table>
 *   <caption>Operation mapping onto JTA and Spring</caption>
 *   <tr><th>Contract</th><th>JTA ({@code jakarta.transaction})</th>
 *       <th>Spring ({@code PlatformTransactionManager})</th></tr>
 *   <tr><td>{@link #begin()}</td>
 *       <td>{@code TransactionManager.begin()}; an active transaction is {@code suspend()}ed first and
 *           {@code resume()}d after, or rejected</td>
 *       <td>{@code getTransaction(def)} with {@code PROPAGATION_REQUIRES_NEW}, keeping the
 *           {@code TransactionStatus}</td></tr>
 *   <tr><td>{@link LocalTransaction#commit()} / {@link LocalTransaction#rollback()}</td>
 *       <td>{@code commit()} / {@code rollback()}</td>
 *       <td>{@code commit(status)} / {@code rollback(status)}</td></tr>
 *   <tr><td>{@link LocalTransaction#setRollbackOnly()} / {@link LocalTransaction#isRollbackOnly()}</td>
 *       <td>{@code setRollbackOnly()} / {@code getStatus() == STATUS_MARKED_ROLLBACK}</td>
 *       <td>{@code status.setRollbackOnly()} / {@code status.isRollbackOnly()}</td></tr>
 *   <tr><td>{@link #current()}, {@link #isActive()}</td>
 *       <td>{@code getStatus() != STATUS_NO_TRANSACTION}</td>
 *       <td>{@code TransactionSynchronizationManager.isActualTransactionActive()}</td></tr>
 *   <tr><td>{@code resource(Connection.class)}</td>
 *       <td>the connection of the JTA-enlisted {@code DataSource}, or
 *           {@code TransactionSynchronizationRegistry.getResource(key)}</td>
 *       <td>{@code DataSourceUtils.getConnection(dataSource)}</td></tr>
 * </table>
 *
 * <h2>Null-safety contracts</h2>
 * <p>No operation of this interface takes an argument; {@link LocalTransaction#resource(Class)} throws
 * {@link IllegalArgumentException} for a {@code null} type.</p>
 */
public interface TransactionBoundary {

    /**
     * Starts a new top-level local transaction on the calling thread and returns it. {@link #current()} returns it
     * until it is committed or rolled back.
     *
     * @return the new transaction; never {@code null}
     * @throws IllegalStateException if a transaction is already active on the thread and the implementation
     *                               rejects rather than suspends it
     * @throws RuntimeException      if the underlying resource cannot start a transaction (for example no
     *                               connection is available)
     */
    LocalTransaction begin();

    /**
     * Returns the transaction begun through this boundary that is active on the calling thread.
     *
     * @return the active transaction, or empty if none is active on this thread; never {@code null}
     */
    Optional<LocalTransaction> current();

    /**
     * Reports whether a transaction begun through this boundary is active on the calling thread.
     *
     * @return {@code current().isPresent()}
     */
    default boolean isActive() {
        return current().isPresent();
    }
}
