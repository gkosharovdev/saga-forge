package io.github.gkosharovdev.sagaforge.storage.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.github.gkosharovdev.sagaforge.core.spi.LocalTransaction;
import io.github.gkosharovdev.sagaforge.core.spi.SagaContextRecord;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaLifecycleEntry;
import io.github.gkosharovdev.sagaforge.core.spi.SagaLifecycleEventKind;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.core.spi.StepRecord;

/**
 * {@link PostgresStorageProvider} joining a {@link JdbcTransactionBoundary} transaction against a real PostgreSQL
 * (specs/004-saga-engine tasks.md T063; G-ENG-03, FR-022, FR-023, G-003-23). A client write on the bound connection
 * and the instance write commit or roll back together; the context store's own transaction survives the rollback.
 *
 * <p><strong>Container suite</strong>: tagged {@code container}, compiled by the default build and run only with the
 * {@code container-tests} profile.</p>
 */
@Tag("container")
class JoinedTransactionContainerTest {

    private static final String CLIENT_TABLE = "joined_client_write";
    private static final String SAGA_TYPE = "com.example.OrderProcessingSaga";
    private static final Instant NOW = Instant.parse("2026-01-01T10:15:30.123456Z").truncatedTo(ChronoUnit.MICROS);

    private DataSource dataSource;
    private JdbcTransactionBoundary boundary;
    private PostgresStorageProvider provider;

    @BeforeEach
    void resetDatabase() throws SQLException {
        PostgresTestSupport.reset();
        dataSource = PostgresTestSupport.newDataSource();
        boundary = new JdbcTransactionBoundary(dataSource);
        provider = new PostgresStorageProvider(dataSource, boundary);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS " + CLIENT_TABLE + " (id text PRIMARY KEY)");
            statement.execute("DELETE FROM " + CLIENT_TABLE);
        }
    }

    private static SagaInstance created(String sagaId) {
        return new SagaInstance(sagaId, SAGA_TYPE, Map.of(), Map.of("orderId", "o-" + sagaId), SagaStatus.CREATED,
                NOW, NOW, Map.of("handleOrderCreated", StepRecord.notRun()), null, null);
    }

    private static SagaLifecycleEntry createdEntry(String sagaId) {
        return new SagaLifecycleEntry(sagaId, SagaLifecycleEventKind.CREATED, null, List.of(), null, null, NOW);
    }

    private static void clientWrite(LocalTransaction transaction, String id) {
        Connection connection = transaction.resource(Connection.class).orElseThrow();
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO " + CLIENT_TABLE + " VALUES (?)")) {
            insert.setString(1, id);
            insert.executeUpdate();
        } catch (SQLException e) {
            throw new UncheckedSqlException("client write failed", e);
        }
    }

    private static List<Map<String, Object>> clientRows() {
        return PostgresTestSupport.queryRows("SELECT id FROM " + CLIENT_TABLE);
    }

    private static List<Map<String, Object>> historyRows(String sagaId) {
        return PostgresTestSupport.queryRows(
                "SELECT sequence_number FROM saga_lifecycle_history WHERE saga_id = ?", sagaId);
    }

    @Test
    @DisplayName("a joined save and a client write on the bound connection commit together")
    void joinedSaveCommitsWithClientWrite() {
        LocalTransaction transaction = boundary.begin();
        clientWrite(transaction, "client-1");
        provider.save(created("s-1"), List.of(createdEntry("s-1")));
        transaction.commit();

        assertThat(clientRows()).hasSize(1);
        assertThat(provider.findById("s-1")).isPresent();
        assertThat(historyRows("s-1")).hasSize(1);
    }

    @Test
    @DisplayName("a joined save and a client write roll back together, leaving no history row")
    void joinedSaveRollsBackWithClientWrite() {
        LocalTransaction transaction = boundary.begin();
        clientWrite(transaction, "client-1");
        provider.save(created("s-1"), List.of(createdEntry("s-1")));
        transaction.rollback();

        assertThat(clientRows()).isEmpty();
        assertThat(provider.findById("s-1")).isEmpty();
        assertThat(historyRows("s-1")).isEmpty();
    }

    @Test
    @DisplayName("a context-store write made during a joined transaction survives that transaction's rollback")
    void contextStoreWriteSurvivesJoinedRollback() {
        provider.save(created("s-1"), List.of(createdEntry("s-1")));
        PostgresSagaContextStore contextStore = new PostgresSagaContextStore(dataSource);

        LocalTransaction transaction = boundary.begin();
        contextStore.save(new SagaContextRecord("s-1", Map.of("paymentId", "p-7"), NOW));
        transaction.rollback();

        assertThat(contextStore.find("s-1")).hasValueSatisfying(
                record -> assertThat(record.values()).isEqualTo(Map.of("paymentId", "p-7")));
    }

    @Test
    @DisplayName("outside a transaction the boundary-built provider commits on its own")
    void withoutTransactionKeepsOwnTransaction() {
        provider.save(created("s-1"), List.of(createdEntry("s-1")));

        assertThat(boundary.isActive()).isFalse();
        assertThat(new PostgresStorageProvider(PostgresTestSupport.newDataSource()).findById("s-1")).isPresent();
        assertThat(historyRows("s-1")).hasSize(1);
    }
}
