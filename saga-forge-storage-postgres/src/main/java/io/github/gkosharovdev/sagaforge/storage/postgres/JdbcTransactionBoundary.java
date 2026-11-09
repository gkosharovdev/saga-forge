package io.github.gkosharovdev.sagaforge.storage.postgres;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;

import javax.sql.DataSource;

import io.github.gkosharovdev.sagaforge.core.spi.LocalTransaction;
import io.github.gkosharovdev.sagaforge.core.spi.TransactionBoundary;

/**
 * A {@link TransactionBoundary} over plain JDBC: each transaction is one {@link Connection} of a {@link DataSource},
 * bound to the thread that began it (contracts/transaction-boundary.md "JdbcTransactionBoundary", FR-020 to FR-023).
 *
 * <ul>
 *   <li>{@link #begin()} takes a connection, remembers its {@code autoCommit} setting, sets it to {@code false} and
 *       binds the transaction to the calling thread. It <strong>never suspends</strong>: if this boundary already has
 *       a transaction bound on the thread, it throws {@link IllegalStateException}.</li>
 *   <li>{@code commit()} and {@code rollback()} end the transaction: they unbind it, commit or roll back the
 *       connection, restore its {@code autoCommit} setting and close it. A driver failure is thrown as
 *       {@link UncheckedSqlException} after the transaction was unbound and the connection closed; a failed commit is
 *       rolled back first. {@code commit()} on a rollback-only transaction rolls back and throws
 *       {@link IllegalStateException}.</li>
 *   <li>{@code resource(Connection.class)} returns the bound connection, for a {@link PostgresStorageProvider} built
 *       with this boundary, and for client code on the same {@code DataSource}; callers never commit, roll back,
 *       reconfigure or close it. Any other resource type is empty.</li>
 * </ul>
 *
 * <p><strong>Pool sizing.</strong> {@link PostgresSagaContextStore} never joins and takes a connection of its own for
 * every call, so the pool must allow at least two connections per concurrent unit of work: the transaction's and the
 * context store's.</p>
 *
 * <p>Thread-safe: transactions are confined to the thread that began them. The caller supplies the
 * {@code DataSource} and owns its pooling.</p>
 */
public final class JdbcTransactionBoundary implements TransactionBoundary {

    private final DataSource dataSource;
    private final ThreadLocal<JdbcTransaction> bound = new ThreadLocal<>();

    /**
     * @param dataSource where each transaction's connection comes from; the caller owns its pooling
     * @throws IllegalArgumentException if {@code dataSource} is {@code null}
     */
    public JdbcTransactionBoundary(DataSource dataSource) {
        if (dataSource == null) {
            throw new IllegalArgumentException("dataSource must not be null");
        }
        this.dataSource = dataSource;
    }

    /**
     * @throws IllegalStateException if a transaction of this boundary is bound on the calling thread
     * @throws UncheckedSqlException if no connection can be taken or configured; nothing is bound
     */
    @Override
    public LocalTransaction begin() {
        if (bound.get() != null) {
            throw new IllegalStateException("A JDBC transaction is already bound on thread "
                    + Thread.currentThread().getName() + "; JdbcTransactionBoundary never suspends");
        }
        JdbcTransaction transaction = open();
        bound.set(transaction);
        return transaction;
    }

    @Override
    public Optional<LocalTransaction> current() {
        return Optional.ofNullable(bound.get());
    }

    private JdbcTransaction open() {
        Connection connection = connect();
        try {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            return new JdbcTransaction(connection, autoCommit);
        } catch (SQLException e) {
            closeQuietly(connection, e);
            throw new UncheckedSqlException("could not begin a JDBC transaction", e);
        }
    }

    private Connection connect() {
        try {
            return dataSource.getConnection();
        } catch (SQLException e) {
            throw new UncheckedSqlException("could not take a connection for a JDBC transaction", e);
        }
    }

    private static void closeQuietly(Connection connection, SQLException failure) {
        try {
            connection.close();
        } catch (SQLException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    /** What ending a transaction does to its connection before it is restored and closed. */
    @FunctionalInterface
    private interface Ending {

        void apply(Connection connection) throws SQLException;
    }

    /** One bound connection. Confined to the thread that began it. */
    private final class JdbcTransaction implements LocalTransaction {

        private final Connection connection;
        private final boolean originalAutoCommit;
        private boolean rollbackOnly;
        private boolean ended;

        private JdbcTransaction(Connection connection, boolean originalAutoCommit) {
            this.connection = connection;
            this.originalAutoCommit = originalAutoCommit;
        }

        /**
         * @throws IllegalStateException if the transaction has ended, or was rollback-only (it is rolled back)
         * @throws UncheckedSqlException if the commit fails; the transaction was rolled back and ended
         */
        @Override
        public void commit() {
            requireActive();
            if (rollbackOnly) {
                end("roll back", Connection::rollback);
                throw new IllegalStateException("The transaction was marked rollback-only; it was rolled back");
            }
            end("commit", JdbcTransaction::commitOrRollBack);
        }

        /**
         * @throws IllegalStateException if the transaction has ended
         * @throws UncheckedSqlException if the rollback fails; the transaction ended anyway
         */
        @Override
        public void rollback() {
            requireActive();
            end("roll back", Connection::rollback);
        }

        @Override
        public void setRollbackOnly() {
            requireActive();
            rollbackOnly = true;
        }

        @Override
        public boolean isRollbackOnly() {
            return rollbackOnly;
        }

        /**
         * @return the bound {@link Connection} for {@code Connection.class}, else empty
         * @throws IllegalArgumentException if {@code type} is {@code null}
         * @throws IllegalStateException    if the transaction has ended
         */
        @Override
        public <R> Optional<R> resource(Class<R> type) {
            if (type == null) {
                throw new IllegalArgumentException("type must not be null");
            }
            requireActive();
            return type == Connection.class ? Optional.of(type.cast(connection)) : Optional.empty();
        }

        /** Unbinds, applies {@code ending}, restores {@code autoCommit}, closes; then throws the first failure. */
        private void end(String action, Ending ending) {
            ended = true;
            bound.remove();
            SQLException failure = null;
            try {
                ending.apply(connection);
            } catch (SQLException e) {
                failure = e;
            }
            failure = restoreAndClose(failure);
            if (failure != null) {
                throw new UncheckedSqlException("could not " + action + " the JDBC transaction", failure);
            }
        }

        /** @return {@code failure}, or the first restore/close failure if there was none, others suppressed */
        private SQLException restoreAndClose(SQLException failure) {
            SQLException first = failure;
            try {
                connection.setAutoCommit(originalAutoCommit);
            } catch (SQLException e) {
                first = firstOf(first, e);
            }
            try {
                connection.close();
            } catch (SQLException e) {
                first = firstOf(first, e);
            }
            return first;
        }

        private static SQLException firstOf(SQLException first, SQLException next) {
            if (first == null) {
                return next;
            }
            first.addSuppressed(next);
            return first;
        }

        /** Commits; if the commit fails, rolls back too, so the connection goes back to the pool clean. */
        private static void commitOrRollBack(Connection connection) throws SQLException {
            try {
                connection.commit();
            } catch (SQLException commitFailure) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    commitFailure.addSuppressed(rollbackFailure);
                }
                throw commitFailure;
            }
        }

        private void requireActive() {
            if (ended) {
                throw new IllegalStateException("The JDBC transaction has already ended");
            }
        }
    }
}
