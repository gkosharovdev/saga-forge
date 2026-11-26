package io.github.gkosharovdev.sagaforge.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gkosharovdev.sagaforge.core.execution.harness.InMemorySagaContextStore;
import io.github.gkosharovdev.sagaforge.core.execution.harness.MapHandlerInstanceProvider;
import io.github.gkosharovdev.sagaforge.core.execution.harness.RecordingEventDispatcher;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.SagaHandlingResult;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.publishonce.PackingSaga;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.publishonce.PackingService;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.publishonce.Shipment;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.publishonce.ShipmentInvoiced;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.publishonce.ShipmentPacked;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.publishonce.ShipmentRequested;
import io.github.gkosharovdev.sagaforge.runtime.support.InMemoryTransactionBoundary;
import io.github.gkosharovdev.sagaforge.runtime.support.RecordingLogger;
import io.github.gkosharovdev.sagaforge.runtime.support.TransactionalInMemoryStorageProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Routing with a source instance (specs/014-publish-once contracts/event-receiver-source.md ER1,
 * SR1 to SR3): only the source instance whose step for the event completed is left out.
 */
class EventRouterSourceTest {

  private static final String ORDER = "o-1";

  private final InMemoryTransactionBoundary boundary = new InMemoryTransactionBoundary();
  private final RecordingLogger logger = new RecordingLogger();
  private SagaRuntime runtime;
  private String packingId;

  @BeforeEach
  void startPackingSaga() {
    runtime =
        SagaRuntime.builder()
            .register(PackingSaga.class, PackingService.class, Shipment.class)
            .storage(new TransactionalInMemoryStorageProvider(boundary))
            .contextStore(new InMemorySagaContextStore())
            .transactions(boundary)
            .dispatcher(new RecordingEventDispatcher())
            .handlerInstances(new MapHandlerInstanceProvider().register(new PackingService()))
            .logger(logger)
            .build();
    runtime.start();
    packingId = runtime.receive(new ShipmentRequested(ORDER)).results().get(0).sagaId();
  }

  @Test
  @DisplayName("SR2: the source instance whose step completed gets no result and no log")
  void sourceThatRanItIsLeftOut() {
    int before = logger.byEvent("saga.event.duplicate").size();

    var report = runtime.receive(new ShipmentPacked(ORDER), packingId);

    assertThat(report.results()).isEmpty();
    assertThat(logger.byEvent("saga.event.duplicate")).hasSize(before);
  }

  @Test
  @DisplayName("SR3: without a source, or with another source, the same event is a DUPLICATE")
  void otherSourceUnchanged() {
    SagaHandlingResult withoutSource = runtime.receive(new ShipmentPacked(ORDER)).results().get(0);
    SagaHandlingResult otherSource =
        runtime.receive(new ShipmentPacked(ORDER), "some-other-saga").results().get(0);

    assertThat(withoutSource.outcome()).isEqualTo(HandlingOutcome.DUPLICATE);
    assertThat(otherSource.outcome()).isEqualTo(HandlingOutcome.DUPLICATE);
    assertThat(otherSource.sagaId()).isEqualTo(packingId);
  }

  @Test
  @DisplayName("SR3: a source whose step has not completed runs it as usual")
  void sourceStepNotCompletedRuns() {
    SagaHandlingResult result =
        runtime.receive(new ShipmentInvoiced(ORDER), packingId).results().get(0);

    assertThat(result.sagaId()).isEqualTo(packingId);
    assertThat(result.outcome()).isEqualTo(HandlingOutcome.COMPLETED);
  }

  @Test
  @DisplayName("SR2: a terminal source whose step completed gets no result either")
  void terminalSourceLeftOut() {
    runtime.receive(new ShipmentInvoiced(ORDER));

    assertThat(runtime.receive(new ShipmentInvoiced(ORDER), packingId).results()).isEmpty();
    assertThat(runtime.receive(new ShipmentInvoiced(ORDER)).results().get(0).outcome())
        .isEqualTo(HandlingOutcome.DUPLICATE);
  }

  @Test
  @DisplayName("ER1: a blank source is rejected")
  void blankSourceRejected() {
    assertThatThrownBy(() -> runtime.receive(new ShipmentPacked(ORDER), " "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sourceSagaId");
  }
}
