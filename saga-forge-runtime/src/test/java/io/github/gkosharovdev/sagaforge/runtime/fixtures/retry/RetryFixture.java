package io.github.gkosharovdev.sagaforge.runtime.fixtures.retry;

import io.github.gkosharovdev.sagaforge.core.execution.harness.InMemorySagaContextStore;
import io.github.gkosharovdev.sagaforge.core.execution.harness.MapHandlerInstanceProvider;
import io.github.gkosharovdev.sagaforge.core.execution.harness.RecordingEventDispatcher;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingReport;
import io.github.gkosharovdev.sagaforge.core.spi.SagaContextRecord;
import io.github.gkosharovdev.sagaforge.core.spi.SagaHandlingResult;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.runtime.SagaRuntime;
import io.github.gkosharovdev.sagaforge.runtime.support.InMemoryTransactionBoundary;
import io.github.gkosharovdev.sagaforge.runtime.support.RecordingLogger;
import io.github.gkosharovdev.sagaforge.runtime.support.TransactionalInMemoryStorageProvider;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Engines over {@link RetrySaga} sharing one set of stores, so a second engine can stand in for a restarted
 * process.
 */
public final class RetryFixture {

    public static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final CourierScript script = new CourierScript();
    private final PaymentProvider provider = new PaymentProvider();
    private final DeliveryPayments payments = new DeliveryPayments(provider);
    private final CourierService couriers = new CourierService(script);
    private final InMemoryTransactionBoundary boundary = new InMemoryTransactionBoundary();
    private final TransactionalInMemoryStorageProvider storage = new TransactionalInMemoryStorageProvider(boundary);
    private final InMemorySagaContextStore contextStore = new InMemorySagaContextStore();
    private final RecordingLogger logger = new RecordingLogger();
    private final AtomicInteger sagaIds = new AtomicInteger();
    private final AtomicInteger unitOfWorkIds = new AtomicInteger();

    /** A started engine over the shared stores, waiting through {@code pause}. */
    public SagaRuntime engine(RecordingPause pause) {
        SagaRuntime engine = SagaRuntime.builder()
                .register(RetrySaga.class, DeliveryPayments.class, CourierService.class, Delivery.class)
                .storage(storage)
                .contextStore(contextStore)
                .transactions(boundary)
                .dispatcher(new RecordingEventDispatcher())
                .handlerInstances(new MapHandlerInstanceProvider().register(payments).register(couriers))
                .clock(Clock.fixed(NOW, ZoneOffset.UTC))
                .sagaIds(() -> "saga-" + sagaIds.incrementAndGet())
                .unitOfWorkIds(() -> "uow-" + unitOfWorkIds.incrementAndGet())
                .retryPause(pause)
                .logger(logger)
                .build();
        engine.start();
        return engine;
    }

    /** Receives {@link DeliveryBooked} on {@code engine}; expects exactly one result. */
    public static SagaHandlingResult book(SagaRuntime engine, String bookingId, long amount) {
        HandlingReport report = engine.receive(new DeliveryBooked(bookingId, amount));
        if (report.results().size() != 1) {
            throw new IllegalStateException("Expected one result, got " + report.results());
        }
        return report.results().get(0);
    }

    /** Receives {@link DeliveryCharged} on {@code engine}; expects exactly one result. */
    public static SagaHandlingResult charged(SagaRuntime engine, String bookingId) {
        HandlingReport report = engine.receive(new DeliveryCharged(bookingId));
        if (report.results().size() != 1) {
            throw new IllegalStateException("Expected one result, got " + report.results());
        }
        return report.results().get(0);
    }

    public SagaInstance committed(String sagaId) {
        return storage.committed().findById(sagaId).orElseThrow();
    }

    public Map<String, Object> context(String sagaId) {
        return contextStore.find(sagaId).map(SagaContextRecord::values).orElse(Map.of());
    }

    public CourierScript script() {
        return script;
    }

    public PaymentProvider provider() {
        return provider;
    }

    public DeliveryPayments payments() {
        return payments;
    }

    public TransactionalInMemoryStorageProvider storage() {
        return storage;
    }

    public RecordingLogger logger() {
        return logger;
    }
}
