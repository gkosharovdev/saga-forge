package io.github.gkosharovdev.sagaforge.transport.inmemory;

import java.time.Duration;

import io.github.gkosharovdev.sagaforge.core.execution.harness.InMemorySagaContextStore;
import io.github.gkosharovdev.sagaforge.core.execution.harness.MapHandlerInstanceProvider;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.engine.SagaEngine;
import io.github.gkosharovdev.sagaforge.engine.support.InMemoryTransactionBoundary;
import io.github.gkosharovdev.sagaforge.engine.support.TransactionalInMemoryStorageProvider;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.CardService;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.PaymentService;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.ShippingService;
import io.github.gkosharovdev.sagaforge.transport.inmemory.support.FlakyReceiver;
import io.github.gkosharovdev.sagaforge.transport.inmemory.support.InMemoryMessageStore;
import io.github.gkosharovdev.sagaforge.transport.inmemory.support.TransportAcceptanceSuite;
import io.github.gkosharovdev.sagaforge.transport.inmemory.support.TransportHarness;

/** SC-008, setup 1 (tasks.md T072): plain Java, Saga Forge's own log (the in-memory double here). */
class PlainJavaAcceptanceTest extends TransportAcceptanceSuite {

    @Override
    protected Setup newSetup() {
        return new PlainSetup();
    }

    private static final class PlainSetup implements Setup {

        private final InMemoryTransactionBoundary boundary = new InMemoryTransactionBoundary();
        private final TransactionalInMemoryStorageProvider storage = new TransactionalInMemoryStorageProvider(boundary);
        private final InMemorySagaContextStore contexts = new InMemorySagaContextStore();
        private final InMemoryMessageStore log = new InMemoryMessageStore();
        private final PaymentService payments = new PaymentService();
        private final ShippingService shipping = new ShippingService();
        private final CardService cards = new CardService();
        private InMemoryTransport transport;
        private SagaEngine engine;
        private FlakyReceiver receiver;

        PlainSetup() {
            assembleAndStart();
        }

        private void assembleAndStart() {
            transport = InMemoryTransport.builder().messageStore(log).transactions(boundary).settings(TransportHarness.FAST
                    .withMaxAttempts(3)).build();
            engine = SagaEngine.builder().register(MODEL).storage(storage).contextStore(contexts)
                    .transactions(boundary).dispatcher(transport.dispatcher()).outbox(transport.outbox())
                    .handlerInstances(new MapHandlerInstanceProvider().register(payments).register(shipping)
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
        public boolean hasInstance(Class<?> sagaType, String status) {
            return storage.committed().all().stream().anyMatch(i -> i.sagaType().equals(sagaType.getName())
                    && i.status() == SagaStatus.valueOf(status));
        }

        @Override
        public int instanceCount(Class<?> sagaType) {
            return (int) storage.committed().all().stream().filter(i -> i.sagaType().equals(sagaType.getName()))
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
