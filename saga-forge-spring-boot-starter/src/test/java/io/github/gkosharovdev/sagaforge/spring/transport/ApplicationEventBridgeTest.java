package io.github.gkosharovdev.sagaforge.spring.transport;

import static io.github.gkosharovdev.sagaforge.transport.inmemory.support.TransportHarness.await;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.core.outcome.SagaCompleted;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingReport;
import io.github.gkosharovdev.sagaforge.runtime.support.InMemoryTransactionBoundary;
import io.github.gkosharovdev.sagaforge.transport.inmemory.InMemoryTransport;
import io.github.gkosharovdev.sagaforge.transport.inmemory.TransportSettings;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.Unrouted;
import io.github.gkosharovdev.sagaforge.transport.inmemory.support.InMemoryMessageStore;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * FR-027 (tasks.md T074): delivered events are re-published as application events; listener
 * failures are ignored.
 */
class ApplicationEventBridgeTest {

  @Test
  @DisplayName("each delivered event is published once; a failing publisher changes nothing")
  void republishes() {
    InMemoryMessageStore log = new InMemoryMessageStore();
    InMemoryTransport transport =
        InMemoryTransport.builder()
            .messageStore(log)
            .transactions(new InMemoryTransactionBoundary())
            .settings(TransportSettings.defaults().withPollInterval(Duration.ofMillis(20)))
            .build();
    List<Object> published = new CopyOnWriteArrayList<>();
    new ApplicationEventBridge(
        transport,
        event -> {
          published.add(event);
          if (((Unrouted) event).orderId().equals("listener-fails")) {
            throw new IllegalStateException("listener failed");
          }
        });
    transport.start(event -> new HandlingReport(event, List.of()), null);
    try {
      transport.dispatcher().dispatch(new Unrouted("listener-fails"));
      transport.dispatcher().dispatch(new Unrouted("fine"));

      await(() -> published.size() == 2, Duration.ofSeconds(5), "both publications");
      assertThat(published)
          .containsExactlyInAnyOrder(new Unrouted("listener-fails"), new Unrouted("fine"));
      assertThat(log.all()).allSatisfy(m -> assertThat(m.status().name()).isEqualTo("CONSUMED"));
    } finally {
      transport.stop(Duration.ofSeconds(2));
    }
  }

  @Test
  @DisplayName("011: under after-delivery, a delivered outcome event is published once")
  void republishesOutcomeEvents() throws InterruptedException {
    InMemoryMessageStore log = new InMemoryMessageStore();
    InMemoryTransport transport =
        InMemoryTransport.builder()
            .messageStore(log)
            .transactions(new InMemoryTransactionBoundary())
            .settings(TransportSettings.defaults().withPollInterval(Duration.ofMillis(20)))
            .build();
    List<Object> published = new CopyOnWriteArrayList<>();
    new ApplicationEventBridge(transport, published::add);
    transport.start(event -> new HandlingReport(event, List.of()), null);
    SagaCompleted outcome =
        SagaCompleted.of(
            "com.example.OrderSaga",
            "s-1",
            Map.of("orderId", "o-1"),
            Instant.parse("2026-11-20T10:00:00Z"),
            "complete");
    try {
      transport.dispatcher().dispatch(outcome);

      await(() -> !published.isEmpty(), Duration.ofSeconds(5), "the publication");
      Thread.sleep(100);
      assertThat(published).containsExactly(outcome);
      assertThat(log.all())
          .singleElement()
          .satisfies(
              m -> {
                assertThat(m.status().name()).isEqualTo("CONSUMED");
                assertThat(m.orderingKey()).isEqualTo("saga:s-1");
              });
    } finally {
      transport.stop(Duration.ofSeconds(2));
    }
  }
}
