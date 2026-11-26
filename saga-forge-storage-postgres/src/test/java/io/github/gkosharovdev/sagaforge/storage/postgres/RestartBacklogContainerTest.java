package io.github.gkosharovdev.sagaforge.storage.postgres;

import static io.github.gkosharovdev.sagaforge.transport.inmemory.support.TransportHarness.await;
import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import io.github.gkosharovdev.sagaforge.core.outcome.SagaCompleted;
import io.github.gkosharovdev.sagaforge.core.outcome.SagaOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingReport;
import io.github.gkosharovdev.sagaforge.core.spi.LocalTransaction;
import io.github.gkosharovdev.sagaforge.core.spi.store.MessageKind;
import io.github.gkosharovdev.sagaforge.core.spi.store.MessageQuery;
import io.github.gkosharovdev.sagaforge.core.spi.store.MessageStatus;
import io.github.gkosharovdev.sagaforge.core.spi.store.NewMessage;
import io.github.gkosharovdev.sagaforge.transport.inmemory.InMemoryTransport;
import io.github.gkosharovdev.sagaforge.transport.inmemory.TransportSettings;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.Unrouted;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * SC-005 (specs/007 tasks.md T050): 10,000 unconsumed messages are delivered within 60 s of a
 * restart; and an outcome event committed before the process stopped is delivered once after the
 * restart (specs/011 SC-003, Q8).
 */
@Tag("container")
class RestartBacklogContainerTest {

  private static final int BACKLOG = 10_000;

  @BeforeEach
  void resetDatabase() {
    PostgresTestSupport.reset();
  }

  @Test
  @DisplayName(
      "SC-005: a 10,000-message backlog is consumed within 60 s, and new work flows meanwhile")
  void backlogDeliveredWithinAMinute() {
    HikariDataSource dataSource = PostgresTestSupport.newPooledDataSource(16);
    JdbcTransactionBoundary boundary = new JdbcTransactionBoundary(dataSource);
    PostgresMessageStore log = new PostgresMessageStore(dataSource, boundary);
    LocalTransaction tx = boundary.begin();
    List<NewMessage> entries = new ArrayList<>();
    for (int i = 0; i < BACKLOG; i++) {
      entries.add(
          new NewMessage(
              "m-" + i,
              MessageKind.EVENT,
              Unrouted.class.getName(),
              new Unrouted("o-" + i),
              null,
              Unrouted.class.getName() + "#o-" + (i % 500),
              null,
              null,
              java.time.Instant.now()));
    }
    log.append(tx, entries);
    tx.commit();
    AtomicInteger delivered = new AtomicInteger();
    InMemoryTransport transport =
        InMemoryTransport.builder()
            .messageStore(log)
            .transactions(boundary)
            .settings(TransportSettings.defaults().withWorkers(8))
            .build();

    long started = System.nanoTime();
    transport.start(
        event -> {
          delivered.incrementAndGet();
          return new HandlingReport(event, List.of());
        },
        null);
    try {
      transport.dispatcher().dispatch(new Unrouted("new-work"));
      await(() -> delivered.get() == BACKLOG + 1, Duration.ofSeconds(60), "the backlog");
      long seconds = Duration.ofNanos(System.nanoTime() - started).toSeconds();
      assertThat(seconds).isLessThan(60);
      // The receiver counts a delivery before the relay marks it consumed, so the last few may
      // still be unconsumed for a moment.
      await(
          () ->
              log.query(MessageQuery.builder().statuses(MessageStatus.UNCONSUMED).build())
                  .isEmpty(),
          Duration.ofSeconds(10),
          "the last deliveries to be marked consumed");
    } finally {
      transport.stop(Duration.ofSeconds(5));
      dataSource.close();
    }
  }

  @Test
  @DisplayName(
      "011 SC-003: an outcome event committed before a stop is delivered once after the restart, same eventId")
  void outcomeEventSurvivesRestart() {
    DataSource dataSource = PostgresTestSupport.newDataSource();
    JdbcTransactionBoundary boundary = new JdbcTransactionBoundary(dataSource);
    PostgresMessageStore log = new PostgresMessageStore(dataSource, boundary);
    SagaCompleted outcome =
        SagaCompleted.of(
            "com.example.OrderSaga",
            "s-1",
            Map.of("orderId", "o-1"),
            java.time.Instant.now(),
            "complete");
    LocalTransaction tx = boundary.begin();
    log.append(
        tx,
        List.of(
            new NewMessage(
                outcome.eventId(),
                MessageKind.EVENT,
                SagaCompleted.class.getName(),
                outcome,
                null,
                "saga:s-1",
                "com.example.OrderSaga",
                "s-1",
                java.time.Instant.now())));
    tx.commit();
    List<String> delivered = new CopyOnWriteArrayList<>();

    for (int process = 0; process < 2; process++) {
      InMemoryTransport transport =
          InMemoryTransport.builder().messageStore(log).transactions(boundary).build();
      transport.start(
          event -> {
            delivered.add(((SagaOutcome) event).eventId());
            return new HandlingReport(event, List.of());
          },
          null);
      try {
        await(
            () ->
                log.query(MessageQuery.builder().statuses(MessageStatus.UNCONSUMED).build())
                    .isEmpty(),
            Duration.ofSeconds(10),
            "the outcome event to be consumed");
      } finally {
        transport.stop(Duration.ofSeconds(5));
      }
    }

    assertThat(delivered).containsExactly("s-1:COMPLETED");
  }
}
