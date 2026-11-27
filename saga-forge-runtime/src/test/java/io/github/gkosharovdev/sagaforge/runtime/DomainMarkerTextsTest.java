package io.github.gkosharovdev.sagaforge.runtime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.InvoiceService;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.ReservationService;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.ShipmentService;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.ThreeStepSaga;
import io.github.gkosharovdev.sagaforge.core.execution.harness.InMemorySagaContextStore;
import io.github.gkosharovdev.sagaforge.core.execution.harness.MapHandlerInstanceProvider;
import io.github.gkosharovdev.sagaforge.core.execution.harness.RecordingEventDispatcher;
import io.github.gkosharovdev.sagaforge.runtime.support.CapturingOutbox;
import io.github.gkosharovdev.sagaforge.runtime.support.InMemoryTransactionBoundary;
import io.github.gkosharovdev.sagaforge.runtime.support.TransactionalInMemoryStorageProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the runtime's texts for an object without its domain marker, so moving the marker reads to
 * {@code DomainTypes} (specs/016-marker-spi-jmolecules FR-011) cannot change them. The scanner's
 * texts are pinned by the existing scanner and startup tests.
 */
class DomainMarkerTextsTest {

  /** Neither a domain event nor a command. */
  record Unmarked(String orderId) {}

  private final InMemoryTransactionBoundary boundary = new InMemoryTransactionBoundary();
  private SagaRuntime runtime;

  @BeforeEach
  void setUp() {
    ReservationService reservations = new ReservationService();
    boundary.participant(reservations);
    runtime =
        SagaRuntime.builder()
            .register(
                ThreeStepSaga.class,
                ReservationService.class,
                InvoiceService.class,
                ShipmentService.class)
            .storage(new TransactionalInMemoryStorageProvider(boundary))
            .contextStore(new InMemorySagaContextStore())
            .transactions(boundary)
            .dispatcher(new RecordingEventDispatcher())
            .outbox(CapturingOutbox.afterCommit())
            .handlerInstances(
                new MapHandlerInstanceProvider()
                    .register(reservations)
                    .register(new InvoiceService())
                    .register(new ShipmentService()))
            .build();
    runtime.start();
  }

  @Test
  @DisplayName("publishing an unmarked object names the missing @DomainEvent")
  void publish() {
    assertThatThrownBy(() -> runtime.eventPublisher().publish(new Unmarked("o-1")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(Unmarked.class.getName() + " is not annotated @DomainEvent");
  }

  @Test
  @DisplayName("receiving an unmarked object names the missing @DomainEvent")
  void receive() {
    assertThatThrownBy(() -> runtime.receive(new Unmarked("o-1")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(Unmarked.class.getName() + " is not annotated @DomainEvent");
  }

  @Test
  @DisplayName("sending an unmarked object names the missing @Command")
  void send() {
    assertThatThrownBy(() -> runtime.commandGateway().send(new Unmarked("o-1")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(Unmarked.class.getName() + " is not annotated @Command");
  }
}
