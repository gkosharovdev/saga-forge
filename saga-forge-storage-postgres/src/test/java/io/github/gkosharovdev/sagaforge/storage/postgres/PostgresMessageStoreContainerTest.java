package io.github.gkosharovdev.sagaforge.storage.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gkosharovdev.sagaforge.core.command.CommandId;
import io.github.gkosharovdev.sagaforge.core.compensation.CompensationTriggerKind;
import io.github.gkosharovdev.sagaforge.core.failure.FailureClassification;
import io.github.gkosharovdev.sagaforge.core.outcome.OutcomeTrigger;
import io.github.gkosharovdev.sagaforge.core.outcome.SagaCompensated;
import io.github.gkosharovdev.sagaforge.core.outcome.SagaCompleted;
import io.github.gkosharovdev.sagaforge.core.outcome.SagaFailed;
import io.github.gkosharovdev.sagaforge.core.outcome.SagaOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.TransactionBoundary;
import io.github.gkosharovdev.sagaforge.core.spi.store.LeaderLease;
import io.github.gkosharovdev.sagaforge.core.spi.store.MessageKind;
import io.github.gkosharovdev.sagaforge.core.spi.store.MessageStore;
import io.github.gkosharovdev.sagaforge.core.spi.store.NewMessage;
import io.github.gkosharovdev.sagaforge.core.spi.store.StoredMessage;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.ChargeCard;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.OrderPlaced;
import io.github.gkosharovdev.sagaforge.transport.inmemory.support.MessageStoreContract;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * {@link PostgresMessageStore} against the {@link MessageStoreContract} plus Postgres-only cases
 * (specs/007 tasks.md T048): concurrent lease calls and payload round trips (FR-004), and a
 * takeover waiting for an open lease transaction ({@code specs/013-active-standby-relay} C12).
 */
@Tag("container")
class PostgresMessageStoreContainerTest extends MessageStoreContract {

  private final DataSource dataSource = PostgresTestSupport.newDataSource();
  private final JdbcTransactionBoundary boundary = new JdbcTransactionBoundary(dataSource);
  private final PostgresMessageStore log = new PostgresMessageStore(dataSource, boundary);

  @BeforeEach
  void resetDatabase() {
    PostgresTestSupport.reset();
  }

  @Override
  protected MessageStore log() {
    return log;
  }

  @Override
  protected TransactionBoundary transactions() {
    return boundary;
  }

  @Override
  protected void makeUnreadable(String messageId) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "UPDATE saga_message_store SET payload_type = 'com.example.Removed' WHERE message_id = ?")) {
      statement.setString(1, messageId);
      statement.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  /** A payload with the value types events usually carry. */
  public record Rich(
      String id, BigDecimal amount, long count, boolean flag, List<String> tags, Instant at) {}

  @Test
  @DisplayName("011 research §11: every outcome event kind round-trips through JSONB")
  void outcomeEventRoundTrip() {
    Instant at = Instant.parse("2026-11-20T10:00:00Z");
    Map<String, String> associations = Map.of("orderId", "o-1");
    OutcomeTrigger trigger =
        new OutcomeTrigger(
            CompensationTriggerKind.FINAL_FAILURE,
            "ship",
            FailureClassification.FINAL,
            "com.example.Refused",
            1,
            at);
    List<Object> outcomes =
        List.of(
            SagaCompleted.of("com.example.OrderSaga", "s-1", associations, at, "complete"),
            SagaFailed.inStep(
                "com.example.OrderSaga",
                "s-2",
                associations,
                at,
                "pay",
                FailureClassification.EXHAUSTED,
                "com.example.Busy",
                3),
            SagaFailed.inCompensation(
                "com.example.OrderSaga",
                "s-3",
                associations,
                at,
                "pay",
                FailureClassification.UNCLASSIFIED,
                "java.lang.IllegalStateException",
                1,
                trigger),
            SagaCompensated.of(
                "com.example.OrderSaga",
                "s-4",
                associations,
                at,
                new OutcomeTrigger(
                    CompensationTriggerKind.FAILURE_OUTCOME, "reject", null, null, null, at)));
    appendCommitted(
        outcomes.stream()
            .map(SagaOutcome.class::cast)
            .map(
                outcome ->
                    new NewMessage(
                        outcome.eventId(),
                        MessageKind.EVENT,
                        outcome.getClass().getName(),
                        outcome,
                        null,
                        "saga:" + outcome.sagaId(),
                        null,
                        null,
                        T0))
            .toArray(NewMessage[]::new));

    List<StoredMessage> leased = lease("c", T0, 10).readable();

    assertThat(leased).extracting(StoredMessage::payload).containsExactlyElementsOf(outcomes);
  }

  @Test
  @DisplayName("FR-004: events, outbox commands and rich records round-trip through JSONB")
  void payloadRoundTrip() {
    CommandId commandId = CommandId.of("s-1", "step", ChargeCard.class.getName(), 1);
    Rich rich =
        new Rich(
            "r-1",
            new BigDecimal("12.50"),
            3,
            true,
            List.of("a", "b"),
            Instant.parse("2026-11-11T10:00:00Z"));
    appendCommitted(
        new NewMessage(
            "e",
            MessageKind.EVENT,
            OrderPlaced.class.getName(),
            new OrderPlaced("o-1"),
            null,
            "k1",
            null,
            null,
            T0),
        new NewMessage(
            commandId.value(),
            MessageKind.COMMAND,
            ChargeCard.class.getName(),
            new ChargeCard("i-1"),
            commandId,
            "k2",
            "Saga",
            "s-1",
            T0),
        new NewMessage(
            "r", MessageKind.EVENT, Rich.class.getName(), rich, null, "k3", null, null, T0));

    List<StoredMessage> leased = lease("c", T0, 10).readable();

    assertThat(leased)
        .extracting(StoredMessage::payload)
        .containsExactly(new OrderPlaced("o-1"), new ChargeCard("i-1"), rich);
    assertThat(leased.get(1).commandId()).isEqualTo(commandId);
  }

  @Test
  @DisplayName("concurrent lease calls never lease the same message, and only the head of each key")
  void concurrentConsumers() throws Exception {
    List<NewMessage> entries = new ArrayList<>();
    for (int i = 0; i < 200; i++) {
      entries.add(event("m-" + i, "k" + (i % 20)));
    }
    appendCommitted(entries.toArray(NewMessage[]::new));
    LeaderLease leader =
        log.leaderElection().tryAcquire("c", "host-c", T0, LEADERSHIP).orElseThrow();
    ExecutorService pool = Executors.newFixedThreadPool(4);
    try {
      List<Callable<List<String>>> consumers = new ArrayList<>();
      for (int c = 0; c < 4; c++) {
        consumers.add(
            () ->
                log.lease(leader, T0, LEASE, 50).readable().stream()
                    .map(StoredMessage::messageId)
                    .toList());
      }
      List<String> all = new ArrayList<>();
      for (Future<List<String>> leased : pool.invokeAll(consumers)) {
        all.addAll(leased.get());
      }
      assertThat(all)
          .doesNotHaveDuplicates()
          .hasSize(20)
          .allSatisfy(id -> assertThat(Integer.parseInt(id.substring(2))).isLessThan(20));
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @DisplayName(
      "C12: a takeover waits for the old leader's open lease transaction; its messages keep their lease")
  void leaseBlocksTakeover() throws Exception {
    appendCommitted(event("m", "k"));
    LeaderLease a = log.leaderElection().tryAcquire("A", "host-A", T0, LEADERSHIP).orElseThrow();
    ExecutorService candidate = Executors.newSingleThreadExecutor();
    try (Connection leasing = dataSource.getConnection()) {
      leasing.setAutoCommit(false);
      assertThat(PostgresLeaderElection.holdIfCurrent(leasing, a, T0.plusSeconds(1))).isTrue();
      try (PreparedStatement statement =
          leasing.prepareStatement(
              "UPDATE saga_message_store SET leased_by = 'A', leased_until = ? WHERE message_id = 'm'")) {
        statement.setObject(1, OffsetDateTime.ofInstant(T0.plus(LEASE), ZoneOffset.UTC));
        statement.executeUpdate();
      }

      Future<Optional<LeaderLease>> takeover =
          candidate.submit(
              () ->
                  log.leaderElection().tryAcquire("B", "host-B", T0.plus(LEADERSHIP), LEADERSHIP));
      assertThatThrownBy(() -> takeover.get(500, TimeUnit.MILLISECONDS))
          .isInstanceOf(TimeoutException.class);

      leasing.commit();
      assertThat(takeover.get(10, TimeUnit.SECONDS)).map(LeaderLease::fencingToken).contains(2L);
    } finally {
      candidate.shutdownNow();
    }
    assertThat(
            PostgresTestSupport.queryRows(
                "SELECT leased_by FROM saga_message_store WHERE message_id = ?", "m"))
        .singleElement()
        .satisfies(row -> assertThat(row.get("leased_by")).isEqualTo("A"));
  }
}
