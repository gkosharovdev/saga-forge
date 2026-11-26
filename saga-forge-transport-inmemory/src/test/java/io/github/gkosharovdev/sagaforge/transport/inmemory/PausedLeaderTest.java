package io.github.gkosharovdev.sagaforge.transport.inmemory;

import static io.github.gkosharovdev.sagaforge.transport.inmemory.support.TransportHarness.await;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.core.spi.HandlingReport;
import io.github.gkosharovdev.sagaforge.core.spi.store.MessageStatus;
import io.github.gkosharovdev.sagaforge.runtime.support.InMemoryTransactionBoundary;
import io.github.gkosharovdev.sagaforge.runtime.support.RecordingLogger;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.Numbered;
import io.github.gkosharovdev.sagaforge.transport.inmemory.support.InMemoryMessageStore;
import io.github.gkosharovdev.sagaforge.transport.inmemory.support.PausingMessageStore;
import io.github.gkosharovdev.sagaforge.transport.inmemory.support.TransportHarness;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A leader that stalls cannot cause harm ({@code specs/013-active-standby-relay} US4,
 * contracts/relay-leadership.md T3, FR-006 to FR-010): its stale lease is fenced, and a deposed
 * relay finishes its in-flight deliveries under their message leases without starting new ones.
 * Relays deliver to plain receivers over a {@link PausingMessageStore}.
 */
class PausedLeaderTest {

  private static final Duration WAIT = Duration.ofSeconds(10);

  private final InMemoryMessageStore log = new InMemoryMessageStore();
  private final PausingMessageStore store = new PausingMessageStore(log);
  private final InMemoryTransactionBoundary boundary = new InMemoryTransactionBoundary();
  private final List<Relay> relays = new ArrayList<>();

  /** One relay with a receiver that can hold, then fail or succeed, its first delivery. */
  private final class Relay {
    final InMemoryTransport transport;
    final RecordingLogger logger = new RecordingLogger();
    final List<Object> delivered = new CopyOnWriteArrayList<>();
    final CountDownLatch release = new CountDownLatch(1);
    final String id;
    volatile boolean holdFirst;
    volatile boolean failFirst;

    Relay(TransportSettings settings) {
      transport =
          InMemoryTransport.builder()
              .messageStore(store)
              .transactions(boundary)
              .settings(settings)
              .logger(logger)
              .build();
      transport.start(this::receive, null);
      id = transport.leadership().relayId();
      relays.add(this);
    }

    private HandlingReport receive(Object event) {
      boolean first = delivered.isEmpty();
      delivered.add(event);
      if (first && holdFirst) {
        try {
          release.await(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
        }
        if (failFirst) {
          throw new IllegalStateException("first delivery fails");
        }
      }
      return new HandlingReport(event, List.of());
    }

    RelayRole role() {
      return transport.leadership().role();
    }
  }

  @AfterEach
  void stopAll() {
    relays.forEach(relay -> store.resumeLeasing(relay.id));
    relays.forEach(relay -> store.resumeRenewal(relay.id));
    relays.forEach(relay -> relay.release.countDown());
    relays.forEach(relay -> relay.transport.stop(Duration.ofSeconds(5)));
  }

  @Test
  @DisplayName(
      "T3: a paused leader's resumed lease is fenced; its in-flight delivery finishes before the key's next")
  void pausedLeaderIsFenced() {
    TransportSettings settings = TransportHarness.FAST.withLeaseDuration(Duration.ofSeconds(5));
    Relay a = new Relay(settings);
    a.holdFirst = true;
    Relay b = new Relay(settings);
    a.transport.dispatcher().dispatch(new Numbered("k", 1));
    await(() -> !a.delivered.isEmpty(), WAIT, "A's first delivery");

    store.pauseLeasing(a.id);
    store.pauseRenewal(a.id);
    await(() -> store.isBlockedInLease(a.id), WAIT, "A to stall inside lease");
    await(() -> b.role() == RelayRole.ACTIVE, WAIT, "B to take over");
    b.transport.dispatcher().dispatch(new Numbered("k", 2));
    sleep(Duration.ofMillis(300));
    assertThat(b.delivered).as("the key's head is still A's").isEmpty();

    store.resumeLeasing(a.id);
    await(
        () -> !a.logger.byEvent("saga.transport.leadership.lost").isEmpty(),
        WAIT,
        "A to step down");
    store.resumeRenewal(a.id);
    assertThat(a.logger.byEvent("saga.transport.leadership.lost"))
        .singleElement()
        .satisfies(logged -> assertThat(logged.keys()).containsEntry("cause", "fenced"));
    assertThat(a.role()).isEqualTo(RelayRole.STANDBY);

    a.release.countDown();
    await(() -> b.delivered.size() == 1, WAIT, "B to deliver the key's next message");
    awaitQuiet();
    assertThat(a.delivered).containsExactly(new Numbered("k", 1));
    assertThat(b.delivered).containsExactly(new Numbered("k", 2));
  }

  @Test
  @DisplayName(
      "FR-009: a deposed relay keeps its in-flight message lease and starts no retry; the leader retries")
  void deposedRelayKeepsItsInFlightLease() {
    TransportSettings settings = TransportHarness.FAST;
    Relay a = new Relay(settings);
    a.holdFirst = true;
    a.failFirst = true;
    Relay b = new Relay(settings);
    a.transport.dispatcher().dispatch(new Numbered("k", 1));
    await(() -> !a.delivered.isEmpty(), WAIT, "A's first delivery");

    store.pauseRenewal(a.id);
    await(() -> b.role() == RelayRole.ACTIVE, WAIT, "B to take over");
    sleep(settings.leaseDuration().multipliedBy(2));
    assertThat(b.delivered).as("A still holds the message lease").isEmpty();

    a.release.countDown();
    await(() -> b.delivered.size() == 1, WAIT, "B to retry the failed message");
    awaitQuiet();
    assertThat(a.delivered).containsExactly(new Numbered("k", 1));
    assertThat(b.delivered).containsExactly(new Numbered("k", 1));
    assertThat(log.all()).singleElement().satisfies(m -> assertThat(m.attempts()).isEqualTo(2));
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
