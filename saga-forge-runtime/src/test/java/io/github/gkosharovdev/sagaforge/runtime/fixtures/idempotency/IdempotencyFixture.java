package io.github.gkosharovdev.sagaforge.runtime.fixtures.idempotency;

import io.github.gkosharovdev.sagaforge.core.execution.harness.InMemorySagaContextStore;
import io.github.gkosharovdev.sagaforge.core.execution.harness.MapHandlerInstanceProvider;
import io.github.gkosharovdev.sagaforge.core.execution.harness.RecordingEventDispatcher;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingReport;
import io.github.gkosharovdev.sagaforge.core.spi.SagaHandlingResult;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaObjectProvider;
import io.github.gkosharovdev.sagaforge.runtime.SagaRuntime;
import io.github.gkosharovdev.sagaforge.runtime.support.CapturingOutbox;
import io.github.gkosharovdev.sagaforge.runtime.support.InMemoryTransactionBoundary;
import io.github.gkosharovdev.sagaforge.runtime.support.RecordingLogger;
import io.github.gkosharovdev.sagaforge.runtime.support.TransactionalInMemoryStorageProvider;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

/** An engine over {@link ChargeSaga} and its handlers, with in-memory stores and a fake payment provider. */
public final class IdempotencyFixture {

    public static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final Faults faults = new Faults();
    private final FakePaymentProvider provider = new FakePaymentProvider();
    private final ChargeService charges = new ChargeService(provider, faults);
    private final NotifyService notifications = new NotifyService();
    private final ShipmentRepository shipments = new ShipmentRepository();
    private final InMemoryTransactionBoundary boundary = new InMemoryTransactionBoundary();
    private final TransactionalInMemoryStorageProvider storage = new TransactionalInMemoryStorageProvider(boundary);
    private final RecordingLogger logger = new RecordingLogger();
    private final CapturingOutbox outbox = CapturingOutbox.afterCommit();
    private final SagaRuntime engine;

    public IdempotencyFixture() {
        AtomicInteger sagaIds = new AtomicInteger();
        engine = SagaRuntime.builder()
                .register(ChargeSaga.class, ChargeService.class, NotifyService.class, Shipment.class,
                        ShipmentRepository.class, Order.class, Repacker.class)
                .storage(storage)
                .contextStore(new InMemorySagaContextStore())
                .transactions(boundary)
                .dispatcher(new RecordingEventDispatcher())
                .outbox(outbox)
                .handlerInstances(new MapHandlerInstanceProvider().register(charges).register(notifications)
                        .register(shipments).register(new Repacker()))
                .sagaObjects(new SagaObjectProvider() {
                    @Override
                    public boolean supports(Class<?> sagaType) {
                        return sagaType == ChargeSaga.class;
                    }

                    @Override
                    public Object newInstance(Class<?> sagaType) {
                        return new ChargeSaga(faults);
                    }
                })
                .clock(Clock.fixed(NOW, ZoneOffset.UTC))
                .sagaIds(() -> "saga-" + sagaIds.incrementAndGet())
                .logger(logger)
                .build();
        engine.start();
    }

    public SagaHandlingResult place(String orderId, long amount) {
        return only(engine.receive(new OrderPlaced(orderId, amount)));
    }

    public SagaHandlingResult pack(String orderId) {
        return only(engine.receive(new OrderPacked(orderId)));
    }

    public SagaHandlingResult deliver(String orderId) {
        return only(engine.receive(new OrderDelivered(orderId)));
    }

    private static SagaHandlingResult only(HandlingReport report) {
        if (report.results().size() != 1) {
            throw new IllegalStateException("Expected one result, got " + report.results());
        }
        return report.results().get(0);
    }

    public SagaInstance committed(String sagaId) {
        return storage.committed().findById(sagaId).orElseThrow();
    }

    public Faults faults() {
        return faults;
    }

    public FakePaymentProvider provider() {
        return provider;
    }

    public ChargeService charges() {
        return charges;
    }

    public NotifyService notifications() {
        return notifications;
    }

    public ShipmentRepository shipments() {
        return shipments;
    }

    public TransactionalInMemoryStorageProvider storage() {
        return storage;
    }

    public CapturingOutbox outbox() {
        return outbox;
    }

    public RecordingLogger logger() {
        return logger;
    }

    public SagaRuntime engine() {
        return engine;
    }
}
