package io.github.gkosharovdev.sagaforge.storage.postgres;

import static io.github.gkosharovdev.sagaforge.transport.inmemory.support.TransportHarness.await;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;
import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;
import io.github.gkosharovdev.sagaforge.core.outcome.SagaOutcome;
import io.github.gkosharovdev.sagaforge.runtime.SagaRuntime;
import io.github.gkosharovdev.sagaforge.transport.inmemory.InMemoryTransport;
import io.github.gkosharovdev.sagaforge.transport.inmemory.LeadershipSettings;
import io.github.gkosharovdev.sagaforge.transport.inmemory.RelayRole;
import io.github.gkosharovdev.sagaforge.transport.inmemory.TransportSettings;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.Order;
import io.github.gkosharovdev.sagaforge.transport.inmemory.support.TransportHarness;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Two application instances on one PostgreSQL database ({@code specs/013-active-standby-relay} US1,
 * SC-001, FR-017): each is a {@link SagaRuntime} plus {@link InMemoryTransport} with a {@link
 * DataSource} of its own, as two replicas would be. Both append; only the active relay delivers.
 *
 * <p>The number of SC-001 runs is {@code -Dsaga-forge.sc001.runs} (default 1, about 95 s a run; 20
 * for SC-001).
 */
@Tag("container")
class MultiInstanceAcceptanceTest {

  private static final int ASSOCIATIONS = 100;
  private static final int EVENTS_PER_ASSOCIATION = 100;
  private static final int CHECK_AT = 49;
  private static final Duration WAIT = Duration.ofSeconds(120);
  private static final int RUNS = Integer.getInteger("saga-forge.sc001.runs", 1);

  /**
   * Fast polling, but message leases and leadership long enough that a loaded database causes
   * neither a redelivery nor a failover during the burst.
   */
  private static final TransportSettings SETTINGS =
      TransportHarness.FAST
          .withWorkers(4)
          .withLeaseDuration(Duration.ofSeconds(30))
          .withLeadership(
              new LeadershipSettings(
                  Duration.ofSeconds(5), Duration.ofSeconds(1), Duration.ofMillis(500)));

  /** The marker aggregate of {@link LedgerSaga}'s events; its id is the association. */
  interface Sequenced {
    String orderId();

    int sequence();
  }

  /** Starts {@link LedgerSaga}. */
  @DomainEvent(aggregate = Order.class)
  public record LedgerOpened(@AggregateIdentifier String orderId, int sequence)
      implements Sequenced {}

  /** {@link LedgerSaga}'s middle step. */
  @DomainEvent(aggregate = Order.class)
  public record LedgerChecked(@AggregateIdentifier String orderId, int sequence)
      implements Sequenced {}

  /** Ends {@link LedgerSaga}. */
  @DomainEvent(aggregate = Order.class)
  public record LedgerClosed(@AggregateIdentifier String orderId, int sequence)
      implements Sequenced {}

  /** An event of the same aggregate that no saga reacts to, filling the burst. */
  @DomainEvent(aggregate = Order.class)
  public record LedgerNoted(@AggregateIdentifier String orderId, int sequence)
      implements Sequenced {}

  /** Three steps on one association. */
  @Saga
  public static final class LedgerSaga {

    @SagaId private String sagaId;

    @StartSaga
    @SagaStep(associationProperty = "orderId")
    public void opened(LedgerOpened event) {}

    @SagaStep(associationProperty = "orderId")
    public void checked(LedgerChecked event) {}

    @EndSaga
    @SagaStep(associationProperty = "orderId")
    public void closed(LedgerClosed event) {}
  }

  private final List<Replica> instances = new ArrayList<>();

  @BeforeEach
  void resetDatabase() {
    PostgresTestSupport.reset();
  }

  @AfterEach
  void stopAll() {
    instances.forEach(Replica::stop);
  }

  private Replica instance() {
    return instance(SETTINGS);
  }

  private Replica instance(TransportSettings settings) {
    Replica instance = new Replica(settings, Map.of(), LedgerSaga.class);
    instances.add(instance);
    return instance;
  }

  @Test
  @DisplayName(
      "SC-001: 10,000 events over 100 associations through both instances; no step twice, no reordering")
  void burstThroughBothInstances() {
    for (int attempt = 0; attempt < RUNS; attempt++) {
      int run = attempt;
      PostgresTestSupport.reset();
      Replica a = instance();
      Replica b = instance();
      assertThat(a.role()).isEqualTo(RelayRole.ACTIVE);
      assertThat(b.role()).isEqualTo(RelayRole.STANDBY);

      int published = 0;
      for (int sequence = 0; sequence < EVENTS_PER_ASSOCIATION; sequence++) {
        for (int association = 0; association < ASSOCIATIONS; association++) {
          Replica through = published++ % 2 == 0 ? a : b;
          through.publish(event("o-" + association, sequence));
        }
      }
      int total = ASSOCIATIONS * EVENTS_PER_ASSOCIATION;
      await(
          () -> a.delivered(Sequenced.class).size() + b.delivered(Sequenced.class).size() >= total,
          WAIT,
          "the burst");
      await(this::quiet, WAIT, "the store to become quiet");

      assertThat(b.delivered(Sequenced.class))
          .as("run %d: delivered by the standby", run)
          .isEmpty();
      assertThat(a.delivered(Sequenced.class))
          .as("run %d", run)
          .hasSize(total)
          .doesNotHaveDuplicates();
      Map<String, List<Integer>> perAssociation =
          a.delivered(Sequenced.class).stream()
              .collect(
                  Collectors.groupingBy(
                      Sequenced::orderId,
                      Collectors.mapping(Sequenced::sequence, Collectors.toList())));
      assertThat(perAssociation).hasSize(ASSOCIATIONS);
      await(
          () -> a.delivered(SagaOutcome.class).size() >= ASSOCIATIONS, WAIT, "the outcome events");
      assertThat(a.delivered(SagaOutcome.class)).hasSize(ASSOCIATIONS).doesNotHaveDuplicates();
      assertThat(b.delivered(SagaOutcome.class)).isEmpty();
      perAssociation.forEach(
          (association, sequences) ->
              assertThat(sequences).as("run %d, %s", run, association).isSorted());
      assertThat(
              PostgresTestSupport.queryRows(
                  "SELECT status, count(*) AS n FROM saga_instance GROUP BY status"))
          .singleElement()
          .satisfies(
              row -> {
                assertThat(row.get("status")).isEqualTo("COMPLETED");
                assertThat(((Number) row.get("n")).intValue()).isEqualTo(ASSOCIATIONS);
              });

      instances.forEach(Replica::stop);
      instances.clear();
    }
  }

  @Test
  @DisplayName("FR-017: the same start event appended through both instances creates one instance")
  void startEventThroughBoth() {
    Replica a = instance();
    Replica b = instance();

    a.publish(new LedgerOpened("o-1", 0));
    b.publish(new LedgerOpened("o-1", 0));
    await(() -> a.delivered(Sequenced.class).size() == 2, WAIT, "both start events");
    await(this::quiet, WAIT, "the store to become quiet");

    assertThat(b.delivered(Sequenced.class)).isEmpty();
    assertThat(PostgresTransportHarness.instances(LedgerSaga.class)).hasSize(1);
  }

  @Test
  @DisplayName(
      "SC-002: after the active instance crashes a standby leads within lease + check; nothing is lost")
  void crashHandover() {
    LeadershipSettings leadership =
        new LeadershipSettings(
            Duration.ofSeconds(3), Duration.ofSeconds(1), Duration.ofMillis(500));
    TransportSettings settings =
        TransportHarness.FAST
            .withWorkers(4)
            .withLeaseDuration(Duration.ofSeconds(5))
            .withLeadership(leadership);
    Replica a = instance(settings);
    Replica b = instance(settings);
    // Every saga is started before the crash: a crash between a saga's creation write and its
    // first unit of work strands it CREATED, which a redelivery does not recover (G-ENG-05,
    // G-007-03, G-013-05); this scenario is about the leadership, not that gap.
    for (int association = 0; association < 30; association++) {
      b.publish(new LedgerOpened("o-" + association, 0));
    }
    await(() -> running() == 30, WAIT, "every saga to start");
    for (int association = 0; association < 30; association++) {
      b.publish(new LedgerChecked("o-" + association, 1));
    }
    await(() -> a.delivered(LedgerChecked.class).size() >= 5, WAIT, "A to be mid-stream");

    a.dataSource.cut();
    long crashed = System.nanoTime();
    await(() -> b.role() == RelayRole.ACTIVE, WAIT, "B to take over");
    Duration handover = Duration.ofNanos(System.nanoTime() - crashed);
    for (int association = 0; association < 30; association++) {
      b.publish(new LedgerClosed("o-" + association, 2));
    }
    await(() -> completed() == 30, WAIT, "every saga to complete");
    await(this::quiet, WAIT, "the store to become quiet");

    assertThat(handover)
        .isLessThanOrEqualTo(
            leadership.leaseDuration().plus(leadership.checkInterval()).plusMillis(500));
    assertThat(
            PostgresTestSupport.queryRows(
                "SELECT count(*) AS n FROM saga_message_store WHERE status <> 'CONSUMED'"))
        .singleElement()
        .satisfies(row -> assertThat(((Number) row.get("n")).intValue()).isZero());
  }

  @Test
  @DisplayName(
      "SC-003: 20 graceful handovers during a stream lose no message; every saga completes")
  void repeatedHandovers() {
    int perRound = 10;
    int rounds = 20;
    Replica active = instance();
    Replica standby = instance();
    for (int round = 0; round < rounds; round++) {
      for (int i = 0; i < perRound; i++) {
        String association = "o-" + (round * perRound + i);
        standby.publish(new LedgerOpened(association, 0));
        standby.publish(new LedgerChecked(association, 1));
      }
      active.stop();
      instances.remove(active);
      Replica leading = standby;
      await(() -> leading.role() == RelayRole.ACTIVE, WAIT, "the standby to take over");
      for (int i = 0; i < perRound; i++) {
        leading.publish(new LedgerClosed("o-" + (round * perRound + i), 2));
      }
      active = leading;
      standby = instance();
    }
    await(() -> completed() == rounds * perRound, WAIT, "every saga to complete");
    await(this::quiet, WAIT, "the store to become quiet");

    assertThat(
            PostgresTestSupport.queryRows(
                "SELECT count(*) AS n FROM saga_message_store WHERE status <> 'CONSUMED'"))
        .singleElement()
        .satisfies(row -> assertThat(((Number) row.get("n")).intValue()).isZero());
    assertThat(PostgresTestSupport.queryRows("SELECT fencing_token FROM saga_message_store_leader"))
        .singleElement()
        .satisfies(
            row ->
                assertThat(((Number) row.get("fencing_token")).longValue())
                    .isGreaterThanOrEqualTo(rounds + 1));
  }

  @Test
  @DisplayName(
      "SC-005: a frozen leader leases nothing once its leadership expired; nothing runs twice")
  void frozenLeader() {
    TransportSettings settings =
        TransportHarness.FAST
            .withWorkers(4)
            .withLeaseDuration(Duration.ofSeconds(20))
            .withLeadership(
                new LeadershipSettings(
                    Duration.ofSeconds(2), Duration.ofMillis(500), Duration.ofMillis(200)));
    Replica a = instance(settings);
    Replica b = instance(settings);
    for (int association = 0; association < 20; association++) {
      b.publish(new LedgerOpened("o-" + association, 0));
    }
    await(() -> running() == 20, WAIT, "every saga to start");
    for (int association = 0; association < 20; association++) {
      b.publish(new LedgerChecked("o-" + association, 1));
    }
    await(() -> a.delivered(LedgerChecked.class).size() >= 5, WAIT, "A to be mid-stream");

    a.dataSource.stall();
    Instant frozen = Instant.now();
    await(() -> b.role() == RelayRole.ACTIVE, WAIT, "B to take over");
    for (int association = 0; association < 20; association++) {
      b.publish(new LedgerClosed("o-" + association, 2));
    }
    sleep(Duration.ofSeconds(2));
    a.dataSource.unstall();
    await(() -> completed() == 20, WAIT, "every saga to complete");
    await(this::quiet, WAIT, "the store to become quiet");
    sleep(Duration.ofSeconds(1));

    assertThat(a.leasesReturningMessages)
        .as("A leased nothing after it froze")
        .allSatisfy(at -> assertThat(at).isBefore(frozen));
    List<Object> all = new ArrayList<>(a.delivered(Sequenced.class));
    all.addAll(b.delivered(Sequenced.class));
    assertThat(all).hasSize(60).doesNotHaveDuplicates();
  }

  private static void sleep(Duration duration) {
    try {
      Thread.sleep(duration.toMillis());
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError("interrupted", interrupted);
    }
  }

  private static int running() {
    return ((Number)
            PostgresTestSupport.queryRows(
                    "SELECT count(*) AS n FROM saga_instance WHERE status = 'RUNNING'")
                .get(0)
                .get("n"))
        .intValue();
  }

  private static int completed() {
    return ((Number)
            PostgresTestSupport.queryRows(
                    "SELECT count(*) AS n FROM saga_instance WHERE status = 'COMPLETED'")
                .get(0)
                .get("n"))
        .intValue();
  }

  private static Object event(String association, int sequence) {
    if (sequence == 0) {
      return new LedgerOpened(association, sequence);
    }
    if (sequence == CHECK_AT) {
      return new LedgerChecked(association, sequence);
    }
    if (sequence == EVENTS_PER_ASSOCIATION - 1) {
      return new LedgerClosed(association, sequence);
    }
    return new LedgerNoted(association, sequence);
  }

  private boolean quiet() {
    return PostgresTestSupport.queryRows(
            "SELECT 1 FROM saga_message_store WHERE status = 'UNCONSUMED' LIMIT 1")
        .isEmpty();
  }
}
