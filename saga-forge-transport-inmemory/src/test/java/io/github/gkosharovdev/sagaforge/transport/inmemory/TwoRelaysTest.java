package io.github.gkosharovdev.sagaforge.transport.inmemory;

import static io.github.gkosharovdev.sagaforge.transport.inmemory.support.TransportHarness.await;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.core.execution.harness.InMemorySagaContextStore;
import io.github.gkosharovdev.sagaforge.core.execution.harness.MapHandlerInstanceProvider;
import io.github.gkosharovdev.sagaforge.core.spi.LocalTransaction;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.core.spi.store.MessageKind;
import io.github.gkosharovdev.sagaforge.core.spi.store.MessageStatus;
import io.github.gkosharovdev.sagaforge.core.spi.store.NewMessage;
import io.github.gkosharovdev.sagaforge.runtime.SagaRuntime;
import io.github.gkosharovdev.sagaforge.runtime.support.InMemoryTransactionBoundary;
import io.github.gkosharovdev.sagaforge.runtime.support.TransactionalInMemoryStorageProvider;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.BillingSaga;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.CardService;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.InvoiceIssued;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.Numbered;
import io.github.gkosharovdev.sagaforge.transport.inmemory.support.InMemoryMessageStore;
import io.github.gkosharovdev.sagaforge.transport.inmemory.support.MutableClock;
import io.github.gkosharovdev.sagaforge.transport.inmemory.support.TransportHarness;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Two application instances over one message store ({@code specs/013-active-standby-relay} US1,
 * contracts/relay-leadership.md T1 and T5): both append, only the active relay delivers. Each
 * instance is a runtime plus transport over shared saga storage, as two replicas over one database.
 */
class TwoRelaysTest {

  private static final Duration WAIT = Duration.ofSeconds(10);

  private final InMemoryTransactionBoundary boundary = new InMemoryTransactionBoundary();
  private final TransactionalInMemoryStorageProvider storage =
      new TransactionalInMemoryStorageProvider(boundary);
  private final InMemorySagaContextStore contexts = new InMemorySagaContextStore();
  private final InMemoryMessageStore log = new InMemoryMessageStore();
  private final CardService cards = new CardService();
  private final List<Instance> instances = new ArrayList<>();

  /** One replica: its own runtime and transport, recording what its relay delivered. */
  private final class Instance {
    final String name;
    final InMemoryTransport transport;
    final SagaRuntime runtime;
    final List<Object> delivered = new CopyOnWriteArrayList<>();
    final List<Object> failed = new CopyOnWriteArrayList<>();
    volatile Predicate<Object> failing = event -> false;

    Instance(String name, TransportSettings settings) {
      this(name, settings, Clock.systemUTC());
    }

    Instance(String name, TransportSettings settings, Clock clock) {
      this.name = name;
      transport =
          InMemoryTransport.builder()
              .messageStore(log)
              .transactions(boundary)
              .settings(settings)
              .clock(clock)
              .build();
      runtime =
          SagaRuntime.builder()
              .register(BillingSaga.class, CardService.class)
              .storage(storage)
              .contextStore(contexts)
              .transactions(boundary)
              .dispatcher(transport.dispatcher())
              .outbox(transport.outbox())
              .handlerInstances(new MapHandlerInstanceProvider().register(cards))
              .build();
      runtime.start();
      transport.start(
          event -> {
            if (failing.test(event)) {
              failed.add(event);
              throw new IllegalStateException("cannot handle " + event);
            }
            delivered.add(event);
            return runtime.receive(event);
          },
          runtime.commandReceiver());
      instances.add(this);
    }

    RelayRole role() {
      return transport.leadership().role();
    }
  }

  @AfterEach
  void stopAll() {
    instances.forEach(instance -> instance.transport.stop(Duration.ofSeconds(5)));
  }

  @Test
  @DisplayName(
      "T1: 500 messages over 20 keys appended through both are delivered once, in order, by the active relay")
  void onlyTheActiveRelayDelivers() {
    Instance a = new Instance("A", TransportHarness.FAST);
    Instance b = new Instance("B", TransportHarness.FAST);
    assertThat(a.role()).isEqualTo(RelayRole.ACTIVE);
    assertThat(b.role()).isEqualTo(RelayRole.STANDBY);

    for (int i = 0; i < 500; i++) {
      Instance through = i % 2 == 0 ? a : b;
      through.transport.dispatcher().dispatch(new Numbered("k" + (i % 20), i / 20));
    }
    await(() -> a.delivered.size() >= 500, WAIT, "500 deliveries");
    awaitQuiet();

    assertThat(b.delivered).isEmpty();
    assertThat(a.delivered).hasSize(500).doesNotHaveDuplicates();
    Map<String, List<Integer>> perKey =
        a.delivered.stream()
            .map(Numbered.class::cast)
            .collect(
                Collectors.groupingBy(
                    Numbered::orderId,
                    Collectors.mapping(Numbered::sequence, Collectors.toList())));
    assertThat(perKey).hasSize(20);
    perKey.values().forEach(sequences -> assertThat(sequences).isSorted().hasSize(25));
  }

  @Test
  @DisplayName(
      "US1-3: an outbox command stored by a unit of work on the standby is handled by the active relay")
  void outboxCommandFromTheStandby() {
    Instance a = new Instance("A", TransportHarness.FAST);
    Instance b = new Instance("B", TransportHarness.FAST);

    b.runtime.receive(new InvoiceIssued("i-1"));

    await(
        () ->
            storage.committed().all().stream()
                .anyMatch(instance -> instance.status() == SagaStatus.COMPLETED),
        WAIT,
        "the billing saga to complete");
    assertThat(cards.charges())
        .singleElement()
        .satisfies(c -> assertThat(c.invoiceId()).isEqualTo("i-1"));
    assertThat(b.delivered).isEmpty();
    assertThat(a.delivered).isNotEmpty();
  }

  @Test
  @DisplayName("T5: the retention sweep does not run on the standby; it runs once it leads")
  void retentionOnlyOnTheLeader() {
    TransportSettings rarely = withRetention(Duration.ofDays(1));
    TransportSettings often = withRetention(Duration.ofMillis(10));
    Instance a = new Instance("A", rarely);
    await(() -> a.role() == RelayRole.ACTIVE, WAIT, "A to lead");
    sleep(Duration.ofMillis(100));
    Instance b = new Instance("B", often);
    a.transport.dispatcher().dispatch(new Numbered("k", 1));
    awaitQuiet();

    sleep(Duration.ofMillis(300));
    assertThat(log.all())
        .singleElement()
        .satisfies(m -> assertThat(m.status()).isEqualTo(MessageStatus.CONSUMED));

    a.transport.stop(Duration.ofSeconds(5));
    await(() -> b.role() == RelayRole.ACTIVE, WAIT, "B to take over");
    await(() -> log.all().isEmpty(), WAIT, "B's retention sweep");
  }

  @Test
  @DisplayName(
      "T2: after a graceful stop mid-stream the standby leads within one check interval; nothing is lost")
  void gracefulHandover() {
    Instance a = new Instance("A", TransportHarness.FAST);
    Instance b = new Instance("B", TransportHarness.FAST);
    for (int i = 0; i < 200; i++) {
      (i % 2 == 0 ? a : b).transport.dispatcher().dispatch(new Numbered("k" + (i % 10), i / 10));
    }
    await(() -> a.delivered.size() >= 50, WAIT, "A to be mid-stream");

    a.transport.stop(Duration.ofSeconds(5));
    long stopped = System.nanoTime();
    await(() -> b.role() == RelayRole.ACTIVE, WAIT, "B to take over");
    Duration handover = Duration.ofNanos(System.nanoTime() - stopped);
    for (int i = 200; i < 300; i++) {
      b.transport.dispatcher().dispatch(new Numbered("k" + (i % 10), i / 10));
    }
    awaitQuiet();

    assertThat(handover)
        .isLessThanOrEqualTo(
            TransportHarness.FAST.leadership().checkInterval().plus(Duration.ofMillis(200)));
    List<Object> all = new ArrayList<>(a.delivered);
    all.addAll(b.delivered);
    assertThat(all).hasSize(300).doesNotHaveDuplicates();
    assertThat(b.delivered).hasSizeGreaterThanOrEqualTo(100);
  }

  @Test
  @DisplayName(
      "FR-015: a failed message keeps its attempts across a handover and is set aside at maxAttempts")
  void retryStateSurvivesHandover() {
    TransportSettings settings = TransportHarness.FAST.withMaxAttempts(4);
    Instance a = new Instance("A", settings);
    Instance b = new Instance("B", settings);
    Predicate<Object> poison = event -> event instanceof Numbered n && n.orderId().equals("poison");
    a.failing = poison;
    b.failing = poison;

    a.transport.dispatcher().dispatch(new Numbered("poison", 0));
    await(() -> !a.failed.isEmpty(), WAIT, "A's first failed attempt");
    a.transport.stop(Duration.ofSeconds(5));
    await(
        () -> log.all().stream().allMatch(m -> m.status() == MessageStatus.SET_ASIDE),
        WAIT,
        "the message to be set aside");

    assertThat(log.all()).singleElement().satisfies(m -> assertThat(m.attempts()).isEqualTo(4));
    assertThat(b.failed).isNotEmpty();
    assertThat(a.failed.size() + b.failed.size()).isEqualTo(4);
  }

  @Test
  @DisplayName("T4: a message due during a handover is delivered once, by the new leader")
  void dueMessageAcrossHandover() {
    MutableClock clock = new MutableClock(Instant.parse("2026-11-25T10:00:00Z"));
    Instance a = new Instance("A", TransportHarness.FAST, clock);
    Instance b = new Instance("B", TransportHarness.FAST, clock);
    Instant due = clock.instant().plus(Duration.ofMinutes(10));
    LocalTransaction tx = boundary.begin();
    log.append(
        tx,
        List.of(
            new NewMessage(
                "deadline-1",
                MessageKind.EVENT,
                Numbered.class.getName(),
                new Numbered("d", 1),
                null,
                null,
                null,
                null,
                clock.instant(),
                Map.of(),
                due)));
    tx.commit();
    sleep(Duration.ofMillis(200));
    assertThat(a.delivered).isEmpty();

    a.transport.stop(Duration.ofSeconds(5));
    await(() -> b.role() == RelayRole.ACTIVE, WAIT, "B to take over");
    clock.advance(Duration.ofMinutes(10));
    awaitQuiet();

    assertThat(a.delivered).isEmpty();
    assertThat(b.delivered).containsExactly(new Numbered("d", 1));
  }

  @Test
  @DisplayName(
      "US5-1/2: both relays name the same leader and token; after a handover both report the change")
  void bothRelaysReportTheLeader() {
    Instance a = new Instance("A", TransportHarness.FAST);
    Instance b = new Instance("B", TransportHarness.FAST);
    LeadershipStatus fromA = a.transport.leadership();
    await(() -> b.transport.leadership().currentLeader().isPresent(), WAIT, "B to see a leader");
    LeadershipStatus fromB = b.transport.leadership();

    assertThat(fromA.role()).isEqualTo(RelayRole.ACTIVE);
    assertThat(fromB.role()).isEqualTo(RelayRole.STANDBY);
    assertThat(fromB.currentLeader()).isEqualTo(fromA.currentLeader());
    assertThat(fromA.currentLeader())
        .hasValueSatisfying(lease -> assertThat(lease.holderId()).isEqualTo(fromA.relayId()));

    a.transport.stop(Duration.ofSeconds(5));
    await(() -> b.role() == RelayRole.ACTIVE, WAIT, "B to take over");

    LeadershipStatus afterB = b.transport.leadership();
    LeadershipStatus afterA = a.transport.leadership();
    assertThat(afterB.currentLeader())
        .hasValueSatisfying(
            lease -> {
              assertThat(lease.holderId()).isEqualTo(afterB.relayId());
              assertThat(lease.fencingToken())
                  .isGreaterThan(fromA.currentLeader().orElseThrow().fencingToken());
            });
    assertThat(afterA.role()).isEqualTo(RelayRole.STANDBY);
  }

  /** {@link TransportHarness#FAST} keeping consumed messages 1 ms, swept every {@code sweep}. */
  private static TransportSettings withRetention(Duration sweep) {
    TransportSettings fast = TransportHarness.FAST;
    return new TransportSettings(
        fast.workers(),
        fast.maxAttempts(),
        fast.initialBackoff(),
        fast.maxBackoff(),
        fast.leaseDuration(),
        fast.pollInterval(),
        fast.leaseBatchSize(),
        Duration.ofMillis(1),
        sweep,
        fast.leadership());
  }

  private void awaitQuiet() {
    await(
        () ->
            !log.all().isEmpty()
                && log.all().stream().allMatch(m -> m.status() != MessageStatus.UNCONSUMED),
        WAIT,
        "the store to become quiet");
  }

  private static void sleep(Duration duration) {
    try {
      Thread.sleep(duration.toMillis());
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError("interrupted", interrupted);
    }
  }
}
