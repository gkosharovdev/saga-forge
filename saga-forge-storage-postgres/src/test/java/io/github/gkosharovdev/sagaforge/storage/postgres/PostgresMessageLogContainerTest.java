package io.github.gkosharovdev.sagaforge.storage.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.github.gkosharovdev.sagaforge.core.command.CommandId;
import io.github.gkosharovdev.sagaforge.core.spi.TransactionBoundary;
import io.github.gkosharovdev.sagaforge.core.spi.log.LogEntry;
import io.github.gkosharovdev.sagaforge.core.spi.log.LoggedMessage;
import io.github.gkosharovdev.sagaforge.core.spi.log.MessageKind;
import io.github.gkosharovdev.sagaforge.core.spi.log.MessageLog;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.ChargeCard;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.OrderPlaced;
import io.github.gkosharovdev.sagaforge.transport.inmemory.support.MessageLogContract;

/**
 * {@link PostgresMessageLog} against the {@link MessageLogContract} plus Postgres-only cases (specs/007 tasks.md
 * T048): concurrent consumers and payload round trips (FR-004).
 */
@Tag("container")
class PostgresMessageLogContainerTest extends MessageLogContract {

    private final DataSource dataSource = PostgresTestSupport.newDataSource();
    private final JdbcTransactionBoundary boundary = new JdbcTransactionBoundary(dataSource);
    private final PostgresMessageLog log = new PostgresMessageLog(dataSource, boundary);

    @BeforeEach
    void resetDatabase() {
        PostgresTestSupport.reset();
    }

    @Override
    protected MessageLog log() {
        return log;
    }

    @Override
    protected TransactionBoundary transactions() {
        return boundary;
    }

    @Override
    protected void makeUnreadable(String messageId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE saga_message_log SET payload_type = 'com.example.Removed' WHERE message_id = ?")) {
            statement.setString(1, messageId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A payload with the value types events usually carry. */
    public record Rich(String id, BigDecimal amount, long count, boolean flag, List<String> tags, Instant at) {
    }

    @Test
    @DisplayName("FR-004: events, logged commands and rich records round-trip through JSONB")
    void payloadRoundTrip() {
        CommandId commandId = CommandId.of("s-1", "step", ChargeCard.class.getName(), 1);
        Rich rich = new Rich("r-1", new BigDecimal("12.50"), 3, true, List.of("a", "b"),
                Instant.parse("2026-11-11T10:00:00Z"));
        appendCommitted(
                new LogEntry("e", MessageKind.EVENT, OrderPlaced.class.getName(), new OrderPlaced("o-1"), null, "k1",
                        null, null, T0),
                new LogEntry(commandId.value(), MessageKind.COMMAND, ChargeCard.class.getName(),
                        new ChargeCard("i-1"), commandId, "k2", "Saga", "s-1", T0),
                new LogEntry("r", MessageKind.EVENT, Rich.class.getName(), rich, null, "k3", null, null, T0));

        List<LoggedMessage> leased = log.lease("c", T0, LEASE, 10).readable();

        assertThat(leased).extracting(LoggedMessage::payload)
                .containsExactly(new OrderPlaced("o-1"), new ChargeCard("i-1"), rich);
        assertThat(leased.get(1).commandId()).isEqualTo(commandId);
    }

    @Test
    @DisplayName("concurrent consumers never lease the same message, and only the head of each key")
    void concurrentConsumers() throws Exception {
        List<LogEntry> entries = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            entries.add(event("m-" + i, "k" + (i % 20)));
        }
        appendCommitted(entries.toArray(LogEntry[]::new));
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<List<String>>> consumers = new ArrayList<>();
            for (int c = 0; c < 4; c++) {
                String consumer = "c" + c;
                consumers.add(() -> log.lease(consumer, T0, LEASE, 50).readable().stream()
                        .map(LoggedMessage::messageId).toList());
            }
            List<String> all = new ArrayList<>();
            for (Future<List<String>> leased : pool.invokeAll(consumers)) {
                all.addAll(leased.get());
            }
            assertThat(all).doesNotHaveDuplicates().hasSize(20)
                    .allSatisfy(id -> assertThat(Integer.parseInt(id.substring(2))).isLessThan(20));
        } finally {
            pool.shutdownNow();
        }
    }
}
