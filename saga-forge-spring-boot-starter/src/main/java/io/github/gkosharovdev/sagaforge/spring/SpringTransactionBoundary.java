package io.github.gkosharovdev.sagaforge.spring;

import java.sql.Connection;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

import javax.sql.DataSource;

import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import io.github.gkosharovdev.sagaforge.core.spi.LocalTransaction;
import io.github.gkosharovdev.sagaforge.core.spi.TransactionBoundary;

/**
 * A {@link TransactionBoundary} over Spring's {@link PlatformTransactionManager} ({@code specs/007-in-memory-transport}
 * contracts/spring-wiring.md).
 *
 * <ul>
 *   <li>{@link #begin()} starts a transaction with {@code PROPAGATION_REQUIRED}: it <em>joins</em> a Spring transaction
 *       already active on the thread rather than suspending it (the boundary-specific choice G-ENG-12 leaves open).
 *       A joined transaction commits only when the host's outer transaction does; a rollback marks the outer one
 *       rollback-only.</li>
 *   <li>{@link #current()} is the innermost transaction this boundary began on the calling thread.</li>
 *   <li>{@code resource(Connection.class)} is the connection Spring bound to the transaction for the configured
 *       {@link DataSource}, so {@code PostgresStorageProvider} and {@code PostgresMessageStore} write in it.</li>
 * </ul>
 *
 * <p>Thread-safe: transactions are confined to the thread that began them.</p>
 */
public final class SpringTransactionBoundary implements TransactionBoundary {

    private final PlatformTransactionManager transactionManager;
    private final DataSource dataSource;
    private final ThreadLocal<Deque<SpringTransaction>> active = ThreadLocal.withInitial(ArrayDeque::new);

    public SpringTransactionBoundary(PlatformTransactionManager transactionManager, DataSource dataSource) {
        if (transactionManager == null || dataSource == null) {
            throw new IllegalArgumentException("transactionManager and dataSource must not be null");
        }
        this.transactionManager = transactionManager;
        this.dataSource = dataSource;
    }

    @Override
    public LocalTransaction begin() {
        DefaultTransactionDefinition definition = new DefaultTransactionDefinition(
                TransactionDefinition.PROPAGATION_REQUIRED);
        definition.setName("saga-forge");
        SpringTransaction transaction = new SpringTransaction(transactionManager.getTransaction(definition));
        active.get().push(transaction);
        return transaction;
    }

    @Override
    public Optional<LocalTransaction> current() {
        return Optional.ofNullable(active.get().peek());
    }

    private final class SpringTransaction implements LocalTransaction {

        private final TransactionStatus status;
        private boolean ended;

        SpringTransaction(TransactionStatus status) {
            this.status = status;
        }

        @Override
        public void commit() {
            end();
            transactionManager.commit(status);
        }

        @Override
        public void rollback() {
            end();
            transactionManager.rollback(status);
        }

        @Override
        public void setRollbackOnly() {
            status.setRollbackOnly();
        }

        @Override
        public boolean isRollbackOnly() {
            return status.isRollbackOnly();
        }

        @Override
        public <R> Optional<R> resource(Class<R> type) {
            if (type == Connection.class && !ended) {
                return Optional.of(type.cast(DataSourceUtils.getConnection(dataSource)));
            }
            return Optional.empty();
        }

        private void end() {
            if (ended) {
                throw new IllegalStateException("the transaction has already ended");
            }
            ended = true;
            Deque<SpringTransaction> stack = active.get();
            stack.remove(this);
            if (stack.isEmpty()) {
                active.remove();
            }
        }
    }
}
