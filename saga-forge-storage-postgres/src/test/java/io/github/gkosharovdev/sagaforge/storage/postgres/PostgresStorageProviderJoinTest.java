package io.github.gkosharovdev.sagaforge.storage.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.github.gkosharovdev.sagaforge.core.spi.AggregateReference;
import io.github.gkosharovdev.sagaforge.core.spi.LocalTransaction;
import io.github.gkosharovdev.sagaforge.core.spi.SagaConcurrencyConflictException;
import io.github.gkosharovdev.sagaforge.core.spi.SagaContextRecord;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaLifecycleEntry;
import io.github.gkosharovdev.sagaforge.core.spi.SagaLifecycleEventKind;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.core.spi.StepRecord;
import io.github.gkosharovdev.sagaforge.core.spi.TransactionBoundary;
import io.github.gkosharovdev.sagaforge.storage.postgres.JdbcFake.ConnectionLog;
import io.github.gkosharovdev.sagaforge.storage.postgres.JdbcFake.Executed;

/**
 * {@link PostgresStorageProvider} joining a {@link JdbcTransactionBoundary} transaction, and keeping 003's own
 * transaction when none is active, over {@link JdbcFake} (specs/004-saga-engine tasks.md T062, FR-022, FR-023,
 * contracts/storage-provider.md).
 */
class PostgresStorageProviderJoinTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final String SAGA_ID = "saga-1";
    private static final String SAGA_TYPE = "com.example.OrderSaga";

    private static final String LOCK_PREVIOUS = "FOR UPDATE OF i";
    private static final String NEXT_SEQUENCE = "MAX(sequence_number)";
    private static final String UPSERT = "INSERT INTO saga_instance";
    private static final String INSERT_HISTORY = "INSERT INTO saga_lifecycle_history";
    private static final String FIND_BY_ID = "WHERE i.saga_id = ?";
    private static final String FIND_BY_ASSOCIATION = "WHERE a.association_property = ?";
    private static final String DELETE = "DELETE FROM saga_instance";

    private final JdbcFake fake = new JdbcFake();
    private final JdbcTransactionBoundary boundary = new JdbcTransactionBoundary(fake.dataSource());
    private final PostgresStorageProvider joining = new PostgresStorageProvider(fake.dataSource(), boundary);

    private static SagaInstance instance(SagaStatus status, Map<String, StepRecord> steps) {
        String outcome = status == SagaStatus.COMPLETED ? "onShipped" : null;
        return new SagaInstance(SAGA_ID, SAGA_TYPE, Map.of(), Map.of("orderId", "order-1"), status, NOW, NOW, steps,
                outcome, null);
    }

    private static SagaInstance running() {
        return instance(SagaStatus.RUNNING, Map.of("onPlaced", StepRecord.completed(
                List.of(AggregateReference.of(String.class, "order-1")))));
    }

    private static SagaLifecycleEntry stepCompleted(String step) {
        return new SagaLifecycleEntry(SAGA_ID, SagaLifecycleEventKind.STEP_COMPLETED, step, List.of(), null, null,
                NOW);
    }

    /** Scripts a new instance: no row to lock, history numbered from {@code next}, the upsert inserted. */
    private void scriptNewInstance(long next) {
        fake.script(LOCK_PREVIOUS, List.of())
                .script(NEXT_SEQUENCE, List.of(JdbcFake.single("coalesce", next)))
                .script(UPSERT, List.of(JdbcFake.single("inserted", true)));
    }

    private static List<Long> historyNumbers(ConnectionLog connection) {
        return connection.statementsContaining(INSERT_HISTORY).stream()
                .flatMap(executed -> executed.rows().stream())
                .map(row -> (Long) row.get(2))
                .toList();
    }

    @Nested
    @DisplayName("joined: inside a JdbcTransactionBoundary transaction")
    class Joined {

        @Test
        @DisplayName("save runs every statement on the bound connection and never ends or reconfigures it")
        void saveUsesBoundConnection() {
            scriptNewInstance(5);
            LocalTransaction transaction = boundary.begin();

            joining.save(running(), List.of(stepCompleted("onPlaced"), stepCompleted("onPaid")));

            ConnectionLog bound = fake.onlyConnection();
            assertThat(bound.calls()).containsExactly("setAutoCommit(false)");
            assertThat(bound.isClosed()).isFalse();
            assertThat(bound.statements()).extracting(Executed::sql).satisfiesExactly(
                    sql -> assertThat(sql).contains(LOCK_PREVIOUS),
                    sql -> assertThat(sql).contains(NEXT_SEQUENCE),
                    sql -> assertThat(sql).contains(UPSERT),
                    sql -> assertThat(sql).startsWith("DELETE FROM saga_association"),
                    sql -> assertThat(sql).startsWith("INSERT INTO saga_association"),
                    sql -> assertThat(sql).contains(INSERT_HISTORY));
            transaction.commit();
            assertThat(bound.calls()).containsExactly("setAutoCommit(false)", "commit", "setAutoCommit(true)",
                    "close");
        }

        @Test
        @DisplayName("history rows are inserted in the given order, numbered from the scripted MAX + 1")
        void historyNumberedFromScriptedNext() {
            scriptNewInstance(5);
            LocalTransaction transaction = boundary.begin();

            joining.save(running(), List.of(stepCompleted("onPlaced"), stepCompleted("onPaid")));

            ConnectionLog bound = fake.onlyConnection();
            assertThat(historyNumbers(bound)).containsExactly(5L, 6L);
            assertThat(bound.statementsContaining(INSERT_HISTORY)).singleElement()
                    .satisfies(executed -> assertThat(executed.rows()).extracting(row -> row.get(4))
                            .containsExactly("onPlaced", "onPaid"));
            transaction.rollback();
        }

        @Test
        @DisplayName("findById, findByAssociation and delete use the bound connection")
        void readsAndDeleteUseBoundConnection() {
            SagaInstance stored = running();
            fake.script(FIND_BY_ID, List.of(JdbcFake.instanceRow(stored)))
                    .script(FIND_BY_ASSOCIATION, List.of(JdbcFake.instanceRow(stored)));
            LocalTransaction transaction = boundary.begin();

            Optional<SagaInstance> found = joining.findById(SAGA_ID);
            List<SagaInstance> correlated = joining.findByAssociation("orderId", "order-1");
            joining.delete(SAGA_ID);

            assertThat(found).contains(stored);
            assertThat(correlated).containsExactly(stored);
            ConnectionLog bound = fake.onlyConnection();
            assertThat(bound.statements()).hasSize(3);
            assertThat(bound.statementsContaining(DELETE)).singleElement()
                    .satisfies(executed -> assertThat(executed.parameters()).containsEntry(1, SAGA_ID));
            assertThat(bound.calls()).containsExactly("setAutoCommit(false)");
            transaction.rollback();
        }

        @Test
        @DisplayName("a SQLException is UncheckedSqlException, marks rollback-only and leaves the connection open")
        void sqlExceptionMarksRollbackOnly() {
            scriptNewInstance(0);
            SQLException failure = new SQLException("upsert failed");
            fake.failOn(UPSERT, failure);
            LocalTransaction transaction = boundary.begin();

            assertThatThrownBy(() -> joining.save(running(), List.of()))
                    .isInstanceOf(UncheckedSqlException.class).hasCause(failure);

            assertThat(transaction.isRollbackOnly()).isTrue();
            assertThat(fake.onlyConnection().isClosed()).isFalse();
            assertThat(fake.onlyConnection().calls()).containsExactly("setAutoCommit(false)");
            transaction.rollback();
        }

        @Test
        @DisplayName("a read's SQLException also marks the transaction rollback-only")
        void readFailureMarksRollbackOnly() {
            fake.failOn(FIND_BY_ID, new SQLException("read failed"));
            LocalTransaction transaction = boundary.begin();

            assertThatThrownBy(() -> joining.findById(SAGA_ID)).isInstanceOf(UncheckedSqlException.class);

            assertThat(transaction.isRollbackOnly()).isTrue();
            transaction.rollback();
        }

        @Test
        @DisplayName("a forward-transition violation is a ConcurrentSagaUpdateException, a concurrency conflict")
        void forwardTransitionViolation() {
            SagaInstance completed = instance(SagaStatus.COMPLETED, Map.of());
            fake.script(LOCK_PREVIOUS, List.of(JdbcFake.instanceRow(completed)));
            LocalTransaction transaction = boundary.begin();

            assertThatThrownBy(() -> joining.save(instance(SagaStatus.RUNNING, Map.of()), List.of()))
                    .isInstanceOf(ConcurrentSagaUpdateException.class)
                    .isInstanceOf(SagaConcurrencyConflictException.class);

            assertThat(fake.onlyConnection().statementsContaining(UPSERT)).isEmpty();
            assertThat(fake.onlyConnection().isClosed()).isFalse();
            transaction.rollback();
        }

        @Test
        @DisplayName("an active transaction without a Connection resource is an IllegalStateException")
        void missingConnectionResource() {
            PostgresStorageProvider overForeignBoundary =
                    new PostgresStorageProvider(fake.dataSource(), new ResourcelessBoundary());

            assertThatThrownBy(() -> overForeignBoundary.findById(SAGA_ID))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("java.sql.Connection");
            assertThat(fake.connections()).isEmpty();
        }

        @Test
        @DisplayName("PostgresSagaContextStore.save takes a connection of its own while a transaction is bound")
        void contextStoreNeverJoins() {
            LocalTransaction transaction = boundary.begin();

            new PostgresSagaContextStore(fake.dataSource())
                    .save(new SagaContextRecord(SAGA_ID, Map.of("amount", 250L), NOW));

            assertThat(fake.connections()).hasSize(2);
            ConnectionLog bound = fake.connections().get(0);
            ConnectionLog own = fake.connections().get(1);
            assertThat(bound.statements()).isEmpty();
            assertThat(own.statementsContaining("INSERT INTO saga_context")).hasSize(1);
            assertThat(own.calls()).containsExactly("setAutoCommit(false)", "commit", "setAutoCommit(true)", "close");
            transaction.rollback();
        }
    }

    @Nested
    @DisplayName("not joined")
    class NotJoined {

        @Test
        @DisplayName("with no active transaction, save keeps 003's own-connection transaction")
        void boundaryWithoutTransaction() {
            scriptNewInstance(0);

            joining.save(running(), List.of(stepCompleted("onPlaced")));

            assertThat(fake.onlyConnection().calls())
                    .containsExactly("setAutoCommit(false)", "commit", "setAutoCommit(true)", "close");
            assertThat(historyNumbers(fake.onlyConnection())).containsExactly(0L);
        }

        @Test
        @DisplayName("built from a DataSource only, save keeps 003's own-connection transaction")
        void dataSourceOnly() {
            scriptNewInstance(0);

            new PostgresStorageProvider(fake.dataSource()).save(running(), List.of());

            assertThat(fake.onlyConnection().calls())
                    .containsExactly("setAutoCommit(false)", "commit", "setAutoCommit(true)", "close");
        }

        @Test
        @DisplayName("a failing own-connection save rolls back, restores and closes")
        void ownTransactionFailure() {
            scriptNewInstance(0);
            fake.failOn(UPSERT, new SQLException("upsert failed"));

            assertThatThrownBy(() -> joining.save(running(), List.of())).isInstanceOf(UncheckedSqlException.class);

            assertThat(fake.onlyConnection().calls())
                    .containsExactly("setAutoCommit(false)", "rollback", "setAutoCommit(true)", "close");
        }

        @Test
        @DisplayName("reads and delete run on a connection of their own, closed afterwards")
        void readsOnOwnConnection() {
            joining.findById(SAGA_ID);
            joining.delete(SAGA_ID);

            assertThat(fake.connections()).hasSize(2)
                    .allSatisfy(connection -> assertThat(connection.calls()).containsExactly("close"));
        }

        @Test
        @DisplayName("argument checks run before any SQL")
        void argumentChecksFirst() {
            LocalTransaction transaction = boundary.begin();

            assertThatThrownBy(() -> joining.save(null, List.of())).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> joining.save(running(), null)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> joining.save(running(), List.of(new SagaLifecycleEntry("other",
                    SagaLifecycleEventKind.CREATED, null, List.of(), null, null, NOW))))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> joining.findById(null)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> joining.findByAssociation(null, "x")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> joining.delete(null)).isInstanceOf(IllegalArgumentException.class);

            assertThat(fake.onlyConnection().statements()).isEmpty();
            assertThat(transaction.isRollbackOnly()).isFalse();
            transaction.rollback();
        }

        @Test
        @DisplayName("null constructor arguments are rejected")
        void nullConstructorArguments() {
            assertThatThrownBy(() -> new PostgresStorageProvider(null, boundary))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new PostgresStorageProvider(fake.dataSource(), null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    /** A boundary whose one always-active transaction holds no resource. */
    private static final class ResourcelessBoundary implements TransactionBoundary {

        private final LocalTransaction transaction = new LocalTransaction() {

            @Override
            public void commit() {
            }

            @Override
            public void rollback() {
            }

            @Override
            public void setRollbackOnly() {
            }

            @Override
            public boolean isRollbackOnly() {
                return false;
            }

            @Override
            public <R> Optional<R> resource(Class<R> type) {
                return Optional.empty();
            }
        };

        @Override
        public LocalTransaction begin() {
            return transaction;
        }

        @Override
        public Optional<LocalTransaction> current() {
            return Optional.of(transaction);
        }
    }
}
