package io.github.gkosharovdev.sagaforge.transport.inmemory;

import io.github.gkosharovdev.sagaforge.core.execution.harness.InMemorySagaContextStore;
import io.github.gkosharovdev.sagaforge.core.execution.harness.MapHandlerInstanceProvider;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.core.spi.ThreadLocalMetadataAccessor;
import io.github.gkosharovdev.sagaforge.runtime.SagaRuntime;
import io.github.gkosharovdev.sagaforge.runtime.support.InMemoryTransactionBoundary;
import io.github.gkosharovdev.sagaforge.runtime.support.TransactionalInMemoryStorageProvider;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.CardService;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.PaymentService;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.ShippingService;
import io.github.gkosharovdev.sagaforge.transport.inmemory.support.FlakyReceiver;
import io.github.gkosharovdev.sagaforge.transport.inmemory.support.InMemoryMessageStore;
import io.github.gkosharovdev.sagaforge.transport.inmemory.support.TransportAcceptanceSuite;
import io.github.gkosharovdev.sagaforge.transport.inmemory.support.TransportHarness;
import java.time.Duration;
import java.util.Map;

/**
 * SC-008, setup 1 (tasks.md T072): plain Java, Saga Forge's own log (the in-memory double here).
 */
class PlainJavaAcceptanceTest extends TransportAcceptanceSuite {

  @Override
  protected Setup newSetup() {
    return new PlainSetup();
  }

  private static final class PlainSetup implements Setup {

    private final InMemoryTransactionBoundary boundary = new InMemoryTransactionBoundary();
    private final TransactionalInMemoryStorageProvider storage =
        new TransactionalInMemoryStorageProvider(boundary);
    private final InMemorySagaContextStore contexts = new InMemorySagaContextStore();
    private final InMemoryMessageStore log = new InMemoryMessageStore();
    private final PaymentService payments = new PaymentService();
    private final ShippingService shipping = new ShippingService();
    private final CardService cards = new CardService();
    private final ThreadLocalMetadataAccessor metadata = new ThreadLocalMetadataAccessor();
    private InMemoryTransport transport;
    private SagaRuntime engine;
    private FlakyReceiver receiver;

    PlainSetup() {
      assembleAndStart();
    }

    private void assembleAndStart() {
      transport =
          InMemoryTransport.builder()
              .messageStore(log)
              .transactions(boundary)
              .settings(TransportHarness.FAST.withMaxAttempts(3))
              .metadataAccessor(metadata)
              .build();
      engine =
          SagaRuntime.builder()
              .register(MODEL)
              .storage(storage)
              .contextStore(contexts)
              .transactions(boundary)
              .dispatcher(transport.dispatcher())
              .outbox(transport.outbox())
              .metadataAccessor(metadata)
              .handlerInstances(
                  new MapHandlerInstanceProvider()
                      .register(payments)
                      .register(shipping)
                      .register(cards))
              .build();
      engine.start();
      receiver = new FlakyReceiver(engine);
      transport.start(receiver, engine.commandReceiver());
    }

    @Override
    public void publish(Object event) {
      engine.eventPublisher().publish(event);
    }

    @Override
    public boolean propagatesMetadata() {
      return true;
    }

    @Override
    public void publish(Object event, Map<String, String> entries) {
      metadata.set(entries);
      try {
        engine.eventPublisher().publish(event);
      } finally {
        metadata.clear();
      }
    }

    @Override
    public Map<String, String> instanceMetadata(Class<?> sagaType) {
      return storage.committed().all().stream()
          .filter(i -> i.sagaType().equals(sagaType.getName()))
          .findFirst()
          .orElseThrow()
          .metadata()
          .asMap();
    }

    @Override
    public boolean hasInstance(Class<?> sagaType, String status) {
      return storage.committed().all().stream()
          .anyMatch(
              i ->
                  i.sagaType().equals(sagaType.getName())
                      && i.status() == SagaStatus.valueOf(status));
    }

    @Override
    public int instanceCount(Class<?> sagaType) {
      return (int)
          storage.committed().all().stream()
              .filter(i -> i.sagaType().equals(sagaType.getName()))
              .count();
    }

    @Override
    public void restart() {
      transport.stop(Duration.ofSeconds(5));
      assembleAndStart();
    }

    @Override
    public void failDeliveriesOf(Class<?> type, int times) {
      receiver.fail(type, times);
    }

    @Override
    public MessageStoreQueries queries() {
      return transport.queries();
    }

    @Override
    public PaymentService payments() {
      return payments;
    }

    @Override
    public CardService cards() {
      return cards;
    }

    @Override
    public void close() {
      transport.stop(Duration.ofSeconds(5));
    }
  }
}
