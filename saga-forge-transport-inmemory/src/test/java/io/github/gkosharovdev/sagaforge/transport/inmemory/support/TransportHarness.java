package io.github.gkosharovdev.sagaforge.transport.inmemory.support;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import io.github.gkosharovdev.sagaforge.core.execution.harness.InMemorySagaContextStore;
import io.github.gkosharovdev.sagaforge.core.execution.harness.MapHandlerInstanceProvider;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.core.spi.store.StoredMessage;
import io.github.gkosharovdev.sagaforge.core.spi.store.MessageStatus;
import io.github.gkosharovdev.sagaforge.engine.SagaEngine;
import io.github.gkosharovdev.sagaforge.engine.support.InMemoryTransactionBoundary;
import io.github.gkosharovdev.sagaforge.engine.support.TransactionalInMemoryStorageProvider;
import io.github.gkosharovdev.sagaforge.transport.inmemory.InMemoryTransport;
import io.github.gkosharovdev.sagaforge.transport.inmemory.TransportSettings;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.CardService;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.PaymentService;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.ShippingService;

/**
 * Engine + {@link InMemoryTransport} + {@link InMemoryMessageStore} + in-memory saga storage, assembled as in
 * contracts/in-memory-transport.md (tasks.md T029). {@link #restart()} builds a new engine and transport over the same
 * storage and log, as a restarted process would. The services are shared across restarts.
 */
public final class TransportHarness implements AutoCloseable {

    /** Fast settings for tests: 2 workers, 10 ms backoff, 1 s lease, 50 ms poll. */
    public static final TransportSettings FAST = TransportSettings.defaults().withWorkers(2)
            .withBackoff(Duration.ofMillis(10), Duration.ofMillis(40)).withLeaseDuration(Duration.ofSeconds(1))
            .withPollInterval(Duration.ofMillis(50)).withHeartbeatInterval(Duration.ofSeconds(1));

    public final InMemoryTransactionBoundary boundary = new InMemoryTransactionBoundary();
    public final TransactionalInMemoryStorageProvider storage = new TransactionalInMemoryStorageProvider(boundary);
    public final InMemorySagaContextStore contexts = new InMemorySagaContextStore();
    public final InMemoryMessageStore log = new InMemoryMessageStore();
    public final PaymentService payments = new PaymentService();
    public final ShippingService shipping = new ShippingService();
    public final CardService cards = new CardService();

    private final Class<?>[] model;
    private final TransportSettings settings;
    private SagaEngine engine;
    private InMemoryTransport transport;
    private RecordingReceiver receiver;

    public TransportHarness(TransportSettings settings, Class<?>... model) {
        this.settings = settings;
        this.model = model;
        assemble();
    }

    private void assemble() {
        transport = InMemoryTransport.builder().messageStore(log).transactions(boundary).settings(settings).build();
        engine = SagaEngine.builder()
                .register(model)
                .storage(storage)
                .contextStore(contexts)
                .transactions(boundary)
                .dispatcher(transport.dispatcher())
                .outbox(transport.outbox())
                .handlerInstances(new MapHandlerInstanceProvider().register(payments).register(shipping)
                        .register(cards))
                .build();
        engine.start();
        receiver = new RecordingReceiver(engine::receive);
    }

    /** Starts delivering. */
    public TransportHarness start() {
        transport.start(receiver, engine.commandReceiver());
        return this;
    }

    /** Stops the current transport (releasing its leases) and assembles a fresh engine and transport. */
    public TransportHarness restart() {
        transport.stop(Duration.ofSeconds(5));
        assemble();
        return start();
    }

    /**
     * Stops the current transport, waiting only {@code grace} for deliveries in progress (its leases are then
     * released), and assembles a fresh engine and transport: a process killed mid-delivery.
     */
    public TransportHarness restartAfterStop(Duration grace) {
        transport.stop(grace);
        assemble();
        return start();
    }

    /** Assembles a fresh engine and transport without stopping the current one, as after a crash. */
    public TransportHarness abandonAndRestart() {
        assemble();
        return start();
    }

    public SagaEngine engine() {
        return engine;
    }

    public InMemoryTransport transport() {
        return transport;
    }

    public RecordingReceiver receiver() {
        return receiver;
    }

    /** Publishes {@code event} outside any unit of work, through the engine's EventPublisher. */
    public void publish(Object event) {
        engine.eventPublisher().publish(event);
    }

    /** Waits until every visible message is consumed or set aside. */
    public void awaitQuiet(Duration timeout) {
        await(() -> !log.all().isEmpty() && log.all().stream()
                .allMatch(m -> m.status() != MessageStatus.UNCONSUMED), timeout, "the store to become quiet");
    }

    /** Waits until {@code condition} holds. */
    public static void await(BooleanSupplier condition, Duration timeout, String what) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Timed out after " + timeout + " waiting for " + what);
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted", interrupted);
            }
        }
    }

    /** @return the committed instances of {@code sagaType} */
    public List<SagaInstance> instancesOf(Class<?> sagaType) {
        return storage.committed().all().stream().filter(i -> i.sagaType().equals(sagaType.getName())).toList();
    }

    /** @return the single committed instance of {@code sagaType} with that status, if any */
    public Optional<SagaInstance> instance(Class<?> sagaType, SagaStatus status) {
        return instancesOf(sagaType).stream().filter(i -> i.status() == status).findFirst();
    }

    /** @return the messages of {@code payloadType} in the store */
    public List<StoredMessage> messagesOf(Class<?> payloadType) {
        return log.all().stream().filter(m -> m.payloadType().equals(payloadType.getName())).toList();
    }

    @Override
    public void close() {
        transport.stop(Duration.ofSeconds(5));
    }
}
