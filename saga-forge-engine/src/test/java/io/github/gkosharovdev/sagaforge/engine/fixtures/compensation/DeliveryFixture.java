package io.github.gkosharovdev.sagaforge.engine.fixtures.compensation;

import io.github.gkosharovdev.sagaforge.core.execution.harness.InMemorySagaContextStore;
import io.github.gkosharovdev.sagaforge.core.execution.harness.MapHandlerInstanceProvider;
import io.github.gkosharovdev.sagaforge.core.execution.harness.RecordingEventDispatcher;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingReport;
import io.github.gkosharovdev.sagaforge.core.spi.SagaHandlingResult;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaLifecycleEventKind;
import io.github.gkosharovdev.sagaforge.core.spi.SagaObjectProvider;
import io.github.gkosharovdev.sagaforge.engine.SagaEngine;
import io.github.gkosharovdev.sagaforge.engine.fixtures.retry.RecordingPause;
import io.github.gkosharovdev.sagaforge.engine.support.CapturingOutbox;
import io.github.gkosharovdev.sagaforge.engine.support.InMemoryTransactionBoundary;
import io.github.gkosharovdev.sagaforge.engine.support.RecordingLogger;
import io.github.gkosharovdev.sagaforge.engine.support.TransactionalInMemoryStorageProvider;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** An engine over {@link DeliverySaga} and its handlers, with in-memory stores and a recording retry pause. */
public final class DeliveryFixture {

    public static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final Journal journal = new Journal();
    private final PaymentService payments = new PaymentService();
    private final DeliveryService deliveries = new DeliveryService();
    private final CourierService couriers = new CourierService();
    private final RecordingPause pause = new RecordingPause();
    private final InMemoryTransactionBoundary boundary = new InMemoryTransactionBoundary();
    private final TransactionalInMemoryStorageProvider storage = new TransactionalInMemoryStorageProvider(boundary);
    private final InMemorySagaContextStore contexts = new InMemorySagaContextStore();
    private final RecordingLogger logger = new RecordingLogger();
    private final CapturingOutbox outbox = CapturingOutbox.afterCommit();
    private final AtomicInteger sagaIds = new AtomicInteger();
    private SagaEngine engine;

    public DeliveryFixture() {
        engine = build(storage);
    }

    /** Replaces the engine with one whose storage calls go through the returned crash injector. */
    public CompensationFaults withFaults() {
        CompensationFaults faults = new CompensationFaults(storage);
        engine = build(faults);
        return faults;
    }

    /** A new engine over the same stores and services, as after a process restart. */
    public DeliveryFixture restart() {
        engine = build(storage);
        return this;
    }

    private SagaEngine build(io.github.gkosharovdev.sagaforge.core.spi.StorageProvider over) {
        SagaEngine built = SagaEngine.builder()
                .register(DeliverySaga.class, PaymentService.class, DeliveryService.class, CourierService.class,
                        Order.class)
                .storage(over)
                .contextStore(contexts)
                .transactions(boundary)
                .dispatcher(new RecordingEventDispatcher())
                .outbox(outbox)
                .handlerInstances(new MapHandlerInstanceProvider().register(payments).register(deliveries)
                        .register(couriers))
                .sagaObjects(new SagaObjectProvider() {
                    @Override
                    public boolean supports(Class<?> sagaType) {
                        return sagaType == DeliverySaga.class;
                    }

                    @Override
                    public Object newInstance(Class<?> sagaType) {
                        return new DeliverySaga(journal);
                    }
                })
                .retryPause(pause)
                .clock(Clock.fixed(NOW, ZoneOffset.UTC))
                .sagaIds(() -> "saga-" + sagaIds.incrementAndGet())
                .logger(logger)
                .build();
        built.start();
        return built;
    }

    public SagaHandlingResult place(String orderId, long amount) {
        return only(engine.receive(new OrderPlaced(orderId, amount)));
    }

    public SagaHandlingResult charged(String orderId, String paymentId) {
        return only(engine.receive(new OrderCharged(orderId, paymentId)));
    }

    /** Places the order and reports its payment: the saga waits for the delivery partner, its charge committed. */
    public String placedAndCharged(String orderId) {
        String sagaId = place(orderId, 100).sagaId();
        charged(orderId, "pay-" + orderId);
        return sagaId;
    }

    public SagaHandlingResult accept(String orderId) {
        return only(engine.receive(new DeliveryAccepted(orderId)));
    }

    public SagaHandlingResult reject(String orderId, String reason) {
        return only(engine.receive(new DeliveryRejected(orderId, reason)));
    }

    public SagaHandlingResult stockUnavailable(String orderId) {
        return only(engine.receive(new StockUnavailable(orderId)));
    }

    public SagaHandlingResult requestCourier(String orderId) {
        return only(engine.receive(new CourierRequested(orderId)));
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

    /** The kinds of the instance's persisted lifecycle entries, in order. */
    public List<SagaLifecycleEventKind> historyKinds(String sagaId) {
        return storage.history(sagaId).stream().map(stored -> stored.entry().kind()).toList();
    }

    public Journal journal() {
        return journal;
    }

    public PaymentService payments() {
        return payments;
    }

    public DeliveryService deliveries() {
        return deliveries;
    }

    public CourierService couriers() {
        return couriers;
    }

    public RecordingPause pause() {
        return pause;
    }

    public TransactionalInMemoryStorageProvider storage() {
        return storage;
    }

    public InMemorySagaContextStore contexts() {
        return contexts;
    }

    public RecordingLogger logger() {
        return logger;
    }

    public CapturingOutbox outbox() {
        return outbox;
    }
}
