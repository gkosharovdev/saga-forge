package io.github.gkosharovdev.sagaforge.example.checkout;

import static io.github.gkosharovdev.sagaforge.transport.inmemory.support.TransportHarness.await;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.core.execution.harness.InMemorySagaContextStore;
import io.github.gkosharovdev.sagaforge.core.execution.harness.MapHandlerInstanceProvider;
import io.github.gkosharovdev.sagaforge.core.metadata.SagaMetadata;
import io.github.gkosharovdev.sagaforge.core.spi.MetadataAccessor;
import io.github.gkosharovdev.sagaforge.runtime.SagaRuntime;
import io.github.gkosharovdev.sagaforge.runtime.support.InMemoryTransactionBoundary;
import io.github.gkosharovdev.sagaforge.runtime.support.TransactionalInMemoryStorageProvider;
import io.github.gkosharovdev.sagaforge.transport.inmemory.InMemoryTransport;
import io.github.gkosharovdev.sagaforge.transport.inmemory.support.InMemoryMessageStore;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Process metadata in a plain-Java assembly, with no Spring ({@code specs/019-process-metadata}
 * T038, SC-006): one {@link MetadataAccessor} over the application's own request context, given to
 * both the runtime and the in-memory transport. The correlation id of the code that places the
 * order is on the thread of every command the checkout's steps send, and reaches Logistics' outbox
 * handler as its {@code SagaMetadata} parameter.
 */
class CheckoutMetadataTest {

  /** The application's request context: what a web filter would set for each request. */
  static final class RequestContext implements MetadataAccessor {

    private static final ThreadLocal<Map<String, String>> CURRENT =
        ThreadLocal.withInitial(Map::of);

    static void set(Map<String, String> values) {
      CURRENT.set(Map.copyOf(values));
    }

    static Map<String, String> current() {
      return CURRENT.get();
    }

    @Override
    public Map<String, String> capture() {
      return CURRENT.get();
    }

    @Override
    public Scope restore(SagaMetadata metadata) {
      if (metadata == null) {
        throw new IllegalArgumentException("metadata must not be null");
      }
      Map<String, String> previous = CURRENT.get();
      CURRENT.set(metadata.asMap());
      return () -> CURRENT.set(previous);
    }
  }

  private final RequestContext requestContext = new RequestContext();
  private final InMemoryTransactionBoundary boundary = new InMemoryTransactionBoundary();
  private final InMemoryTransport transport =
      InMemoryTransport.builder()
          .messageStore(new InMemoryMessageStore())
          .transactions(boundary)
          .metadataAccessor(requestContext)
          .build();
  private final Orders orders = new Orders();
  private final LogisticsService logistics = new LogisticsService();
  private final List<String> seenByCommands = new CopyOnWriteArrayList<>();

  @AfterEach
  void stop() {
    transport.stop(Duration.ofSeconds(5));
  }

  @Test
  @DisplayName("the request's correlation id reaches every command and Logistics' outbox handler")
  void correlationIdTravels() {
    SagaRuntime engine =
        SagaRuntime.builder()
            .register(
                CheckoutSaga.class,
                FulfilmentSaga.class,
                Order.class,
                Orders.class,
                StockReservation.class,
                Payment.class,
                Shipment.class,
                InventoryService.class,
                PaymentService.class,
                LogisticsService.class)
            .storage(new TransactionalInMemoryStorageProvider(boundary))
            .contextStore(new InMemorySagaContextStore())
            .transactions(boundary)
            .dispatcher(transport.dispatcher())
            .outbox(transport.outbox())
            .handlerInstances(
                new MapHandlerInstanceProvider()
                    .register(orders)
                    .register(new InventoryService().stock("sku-mug", 5))
                    .register(new PaymentService(new PaymentProvider()))
                    .register(logistics))
            .metadataAccessor(requestContext)
            .commandInterceptor(
                invocation -> {
                  seenByCommands.add(
                      invocation.command().getClass().getSimpleName()
                          + "="
                          + RequestContext.current().get("correlationId"));
                  invocation.proceed();
                })
            .build();
    engine.start();
    transport.start(engine, engine.commandReceiver());
    Order order =
        new Order(
            "o-1", List.of(new OrderLine("sku-mug", 2)), new BigDecimal("59.90"), "1 Main Street");
    orders.save(order);

    RequestContext.set(Map.of("correlationId", "c-1"));
    try {
      engine.eventPublisher().publish(order.placed());
    } finally {
      RequestContext.set(Map.of());
    }

    await(
        () -> !logistics.correlationIds().isEmpty(),
        Duration.ofSeconds(5),
        "the shipment to be booked");
    assertThat(logistics.correlationIds().values()).containsOnly("c-1");
    assertThat(seenByCommands).isNotEmpty().allMatch(seen -> seen.endsWith("=c-1"));
    assertThat(RequestContext.current()).isEmpty();
  }
}
