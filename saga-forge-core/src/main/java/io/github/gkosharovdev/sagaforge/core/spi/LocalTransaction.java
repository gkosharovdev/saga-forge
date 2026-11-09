package io.github.gkosharovdev.sagaforge.core.spi;

import java.util.Optional;

/**
 * One local transaction begun through {@link TransactionBoundary#begin()}, bound to the thread that began it.
 *
 * <p>Once {@link #commit()} or {@link #rollback()} has been called the transaction is ended:
 * {@link TransactionBoundary#current()} no longer returns it, and {@link #commit()}, {@link #rollback()},
 * {@link #setRollbackOnly()} and {@link #resource(Class)} throw {@link IllegalStateException}. A local transaction
 * has at most one resource of a given type; there is no nesting, no savepoint and no two-phase commit.</p>
 *
 * <p><strong>Implemented by host integrations, never by client code.</strong> See {@link TransactionBoundary} for
 * the JTA and Spring mapping and the joining rules.</p>
 */
public interface LocalTransaction {

    /**
     * Commits the transaction and ends it.
     *
     * <p>On a rollback-only transaction this rolls back, ends the transaction and then throws
     * {@link IllegalStateException}; the engine never asks for that.</p>
     *
     * @throws IllegalStateException if the transaction already ended, or if it is rollback-only (after rolling it
     *                               back)
     * @throws RuntimeException      if the commit fails; the transaction is then rolled back and ended
     */
    void commit();

    /**
     * Rolls the transaction back and ends it.
     *
     * @throws IllegalStateException if the transaction already ended
     * @throws RuntimeException      if the rollback fails; the transaction is ended anyway
     */
    void rollback();

    /**
     * Marks the transaction so that it can only roll back.
     *
     * @throws IllegalStateException if the transaction already ended
     */
    void setRollbackOnly();

    /**
     * Reports whether the transaction is marked rollback-only.
     *
     * @return {@code true} if {@link #setRollbackOnly()} was called on it
     */
    boolean isRollbackOnly();

    /**
     * Returns the transaction's resource of the given type, for example the {@code java.sql.Connection} of a JDBC
     * transaction. Callers use the resource and never commit, roll back, reconfigure or close it.
     *
     * @param type the resource type; must not be {@code null}
     * @param <R>  the resource type
     * @return the resource, or empty if the transaction has none of that type; never {@code null}
     * @throws IllegalArgumentException if {@code type} is {@code null}
     * @throws IllegalStateException    if the transaction already ended
     */
    <R> Optional<R> resource(Class<R> type);
}
