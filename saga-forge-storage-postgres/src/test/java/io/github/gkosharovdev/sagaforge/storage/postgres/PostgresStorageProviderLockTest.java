package io.github.gkosharovdev.sagaforge.storage.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.gkosharovdev.sagaforge.core.spi.LocalTransaction;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.core.spi.StepRecord;
import io.github.gkosharovdev.sagaforge.storage.postgres.JdbcFake.ConnectionLog;
import io.github.gkosharovdev.sagaforge.storage.postgres.JdbcFake.Executed;

/**
 * {@link PostgresStorageProvider#findByIdAndLock(String)} and the lock statement of {@code save}, over {@link JdbcFake}
 * (specs/004-saga-engine tasks.md T080, FR-025, research.md §3).
 */
class PostgresStorageProviderLockTest {

    private static final String SAGA_ID = "saga-1";
    private static final String NO_KEY_LOCK = "FOR NO KEY UPDATE OF i";
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final JdbcFake fake = new JdbcFake();
    private final JdbcTransactionBoundary boundary = new JdbcTransactionBoundary(fake.dataSource());
    private final PostgresStorageProvider joining = new PostgresStorageProvider(fake.dataSource(), boundary);

    private static SagaInstance running() {
        return new SagaInstance(SAGA_ID, "com.example.OrderSaga", Map.of(), Map.of("orderId", "order-1"),
                SagaStatus.RUNNING, NOW, NOW, Map.of("onPlaced", StepRecord.notRun()), null, null);
    }

    @Test
    @DisplayName("joined: one FOR NO KEY UPDATE statement on the bound connection, mapping the scripted row")
    void lockReadsAndMapsRow() {
        SagaInstance stored = running();
        fake.script(NO_KEY_LOCK, List.of(JdbcFake.instanceRow(stored)));
        LocalTransaction transaction = boundary.begin();

        assertThat(joining.findByIdAndLock(SAGA_ID)).contains(stored);

        ConnectionLog bound = fake.onlyConnection();
        assertThat(bound.statements()).singleElement().satisfies(executed -> {
            assertThat(executed.sql()).endsWith(NO_KEY_LOCK);
            assertThat(executed.parameters()).containsEntry(1, SAGA_ID);
        });
        assertThat(bound.calls()).containsExactly("setAutoCommit(false)");
        transaction.rollback();
    }

    @Test
    @DisplayName("joined: a missing instance reads as empty")
    void missingInstanceIsEmpty() {
        LocalTransaction transaction = boundary.begin();

        assertThat(joining.findByIdAndLock(SAGA_ID)).isEmpty();
        transaction.rollback();
    }

    @Test
    @DisplayName("joined: a failing lock statement marks the transaction rollback-only")
    void lockFailureMarksRollbackOnly() {
        fake.failOn(NO_KEY_LOCK, new SQLException("lock wait aborted"));
        LocalTransaction transaction = boundary.begin();

        assertThatThrownBy(() -> joining.findByIdAndLock(SAGA_ID)).isInstanceOf(UncheckedSqlException.class);
        assertThat(transaction.isRollbackOnly()).isTrue();
        transaction.rollback();
    }

    @Test
    @DisplayName("with no joined transaction, or DataSource-only: IllegalStateException before any SQL")
    void noJoinedTransaction() {
        assertThatThrownBy(() -> joining.findByIdAndLock(SAGA_ID)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new PostgresStorageProvider(fake.dataSource()).findByIdAndLock(SAGA_ID))
                .isInstanceOf(IllegalStateException.class);
        assertThat(fake.connections()).isEmpty();
    }

    @Test
    @DisplayName("a null sagaId is rejected")
    void nullSagaId() {
        assertThatThrownBy(() -> joining.findByIdAndLock(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(fake.connections()).isEmpty();
    }

    @Test
    @DisplayName("save locks the previous snapshot FOR NO KEY UPDATE, never with a plain FOR UPDATE")
    void saveLocksWithNoKeyUpdate() {
        fake.script("MAX(sequence_number)", List.of(JdbcFake.single("coalesce", 0L)))
                .script("INSERT INTO saga_instance", List.of(JdbcFake.single("inserted", true)));

        joining.save(running(), List.of());

        List<String> statements = fake.onlyConnection().statements().stream().map(Executed::sql).toList();
        assertThat(statements).anySatisfy(sql -> assertThat(sql).endsWith(NO_KEY_LOCK));
        assertThat(statements).noneMatch(sql -> sql.contains(" FOR UPDATE"));
    }
}
