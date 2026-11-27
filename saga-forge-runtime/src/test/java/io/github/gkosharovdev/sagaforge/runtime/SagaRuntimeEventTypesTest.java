package io.github.gkosharovdev.sagaforge.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gkosharovdev.sagaforge.core.execution.harness.InMemorySagaContextStore;
import io.github.gkosharovdev.sagaforge.core.execution.harness.MapHandlerInstanceProvider;
import io.github.gkosharovdev.sagaforge.core.execution.harness.RecordingEventDispatcher;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.deadline.Order;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.deadline.OrderPlaced;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.deadline.PaymentAuthorized;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.deadline.PaymentDeclined;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.deadline.PaymentWindowSaga;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.deadline.StockReserved;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.deadline.StockService;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.publishonce.BillingSaga;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.publishonce.PackingSaga;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.publishonce.PackingService;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.publishonce.Shipment;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.publishonce.ShipmentInvoiced;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.publishonce.ShipmentPacked;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.publishonce.ShipmentRequested;
import io.github.gkosharovdev.sagaforge.runtime.support.InMemoryTransactionBoundary;
import io.github.gkosharovdev.sagaforge.runtime.support.SchedulingOutbox;
import io.github.gkosharovdev.sagaforge.runtime.support.TransactionalInMemoryStorageProvider;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code SagaRuntime.eventTypes()} (specs/014-publish-once research R4, data-model.md "Saga event
 * types"): the exact step event classes of the registered sagas, fixed at build.
 */
class SagaRuntimeEventTypesTest {

  private final InMemoryTransactionBoundary boundary = new InMemoryTransactionBoundary();

  private SagaRuntime.Builder builder(Class<?>... model) {
    return SagaRuntime.builder()
        .register(model)
        .storage(new TransactionalInMemoryStorageProvider(boundary))
        .contextStore(new InMemorySagaContextStore())
        .transactions(boundary)
        .dispatcher(new RecordingEventDispatcher())
        .handlerInstances(
            new MapHandlerInstanceProvider()
                .register(new PackingService())
                .register(new StockService()));
  }

  @Test
  @DisplayName("the union of every saga's step event classes, the same before and after start")
  void unionOfStepEventClasses() {
    SagaRuntime runtime =
        builder(PackingSaga.class, BillingSaga.class, PackingService.class, Shipment.class).build();

    Set<Class<?>> beforeStart = runtime.eventTypes();
    runtime.start();

    assertThat(beforeStart)
        .containsExactlyInAnyOrder(
            ShipmentRequested.class, ShipmentPacked.class, ShipmentInvoiced.class);
    assertThat(runtime.eventTypes()).isEqualTo(beforeStart);
  }

  @Test
  @DisplayName("deadline expiry steps add no event class; the set is unmodifiable")
  void noDeadlineStepsAndUnmodifiable() {
    SagaRuntime runtime =
        builder(PaymentWindowSaga.class, StockService.class, Order.class)
            .outbox(new SchedulingOutbox(boundary).supportingCommands())
            .build();

    assertThat(runtime.eventTypes())
        .containsExactlyInAnyOrder(
            OrderPlaced.class, StockReserved.class, PaymentDeclined.class, PaymentAuthorized.class);
    assertThatThrownBy(() -> runtime.eventTypes().add(Object.class))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
