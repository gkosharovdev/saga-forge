package io.github.gkosharovdev.sagaforge.example;

import static io.github.gkosharovdev.sagaforge.transport.inmemory.support.TransportHarness.await;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.gkosharovdev.sagaforge.core.execution.harness.InMemorySagaContextStore;
import io.github.gkosharovdev.sagaforge.core.execution.harness.MapHandlerInstanceProvider;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.core.spi.log.MessageStatus;
import io.github.gkosharovdev.sagaforge.engine.SagaEngine;
import io.github.gkosharovdev.sagaforge.engine.support.InMemoryTransactionBoundary;
import io.github.gkosharovdev.sagaforge.engine.support.TransactionalInMemoryStorageProvider;
import io.github.gkosharovdev.sagaforge.example.events.OrderCreatedEvent;
import io.github.gkosharovdev.sagaforge.transport.inmemory.InMemoryTransport;
import io.github.gkosharovdev.sagaforge.transport.inmemory.support.InMemoryMessageLog;

/**
 * SC-004 (specs/007-in-memory-transport, tasks.md T076): the canonical {@link OrderProcessingSaga} runs end to end in
 * one process over the in-memory transport, with no broker and no change to its source. The message log here is the
 * test double; {@code PostgresMessageLog} takes its place in a real application.
 */
class OrderProcessingSagaTransportTest {

    private final InMemoryTransactionBoundary boundary = new InMemoryTransactionBoundary();
    private final TransactionalInMemoryStorageProvider storage = new TransactionalInMemoryStorageProvider(boundary);
    private final InMemoryMessageLog log = new InMemoryMessageLog();
    private final OrderRepository orders = new OrderRepository();
    private final InMemoryTransport transport = InMemoryTransport.builder().log(log).transactions(boundary).build();

    @AfterEach
    void stop() {
        transport.stop(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("an order created outside any unit of work completes through the transport")
    void orderCompletes() {
        SagaEngine engine = SagaEngine.builder()
                .register(OrderProcessingSaga.class, Order.class, OrderRepository.class, PaymentService.class)
                .storage(storage)
                .contextStore(new InMemorySagaContextStore())
                .transactions(boundary)
                .dispatcher(transport.dispatcher())
                .outbox(transport.outbox())
                .handlerInstances(new MapHandlerInstanceProvider().register(orders)
                        .register(new PaymentService(new PaymentProvider())))
                .build();
        engine.start();
        transport.start(engine, engine.commandReceiver());
        orders.save(new Order("order-1"));

        engine.eventPublisher().publish(new OrderCreatedEvent("order-1", new BigDecimal("99.90")));

        await(() -> storage.committed().all().stream().anyMatch(i -> i.status() == SagaStatus.COMPLETED),
                Duration.ofSeconds(5), "the order saga to complete");
        assertThat(orders.load("order-1").status()).isEqualTo(Order.Status.COMPLETED);
        await(() -> log.all().stream().allMatch(m -> m.status() == MessageStatus.CONSUMED), Duration.ofSeconds(5),
                "the log to be consumed");
    }
}
