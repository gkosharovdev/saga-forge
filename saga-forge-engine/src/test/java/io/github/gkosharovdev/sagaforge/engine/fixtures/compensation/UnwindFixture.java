package io.github.gkosharovdev.sagaforge.engine.fixtures.compensation;

import io.github.gkosharovdev.sagaforge.core.execution.harness.InMemorySagaContextStore;
import io.github.gkosharovdev.sagaforge.core.execution.harness.MapHandlerInstanceProvider;
import io.github.gkosharovdev.sagaforge.core.execution.harness.RecordingEventDispatcher;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingReport;
import io.github.gkosharovdev.sagaforge.core.spi.SagaHandlingResult;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaObjectProvider;
import io.github.gkosharovdev.sagaforge.core.spi.StorageProvider;
import io.github.gkosharovdev.sagaforge.engine.SagaEngine;
import io.github.gkosharovdev.sagaforge.engine.support.InMemoryTransactionBoundary;
import io.github.gkosharovdev.sagaforge.engine.support.RecordingLogger;
import io.github.gkosharovdev.sagaforge.engine.support.TransactionalInMemoryStorageProvider;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * An engine over {@link UnwindSaga} or {@link UnwindWithoutBSaga}. {@link #restart()} builds a new engine over the
 * same stores, as a process restart would.
 */
public final class UnwindFixture {

    public static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final Class<?> sagaType;
    private final Function<Journal, Object> sagas;
    private final Journal journal = new Journal();
    private final InMemoryTransactionBoundary boundary = new InMemoryTransactionBoundary();
    private final TransactionalInMemoryStorageProvider storage = new TransactionalInMemoryStorageProvider(boundary);
    private final InMemorySagaContextStore contexts = new InMemorySagaContextStore();
    private final RecordingLogger logger = new RecordingLogger();
    private final AtomicInteger sagaIds = new AtomicInteger();
    private SagaEngine engine;

    private UnwindFixture(Class<?> sagaType, Function<Journal, Object> sagas, StorageProvider overStorage) {
        this.sagaType = sagaType;
        this.sagas = sagas;
        this.engine = build(overStorage);
    }

    public static UnwindFixture withEveryCompensation() {
        return new UnwindFixture(UnwindSaga.class, UnwindSaga::new, null);
    }

    public static UnwindFixture withoutCompensationForB() {
        return new UnwindFixture(UnwindWithoutBSaga.class, UnwindWithoutBSaga::new, null);
    }

    /**
     * Replaces the engine with one whose storage calls go through {@code faults}, wrapping this fixture's
     * transactional store; {@link #restart()} goes back to the plain store.
     */
    public CompensationFaults withFaults() {
        CompensationFaults faults = new CompensationFaults(storage);
        engine = build(faults);
        return faults;
    }

    private SagaEngine build(StorageProvider overStorage) {
        SagaEngine built = SagaEngine.builder()
                .register(sagaType, ChainService.class, Order.class)
                .storage(overStorage == null ? storage : overStorage)
                .contextStore(contexts)
                .transactions(boundary)
                .dispatcher(new RecordingEventDispatcher())
                .handlerInstances(new MapHandlerInstanceProvider().register(new ChainService()))
                .sagaObjects(new SagaObjectProvider() {
                    @Override
                    public boolean supports(Class<?> type) {
                        return type == sagaType;
                    }

                    @Override
                    public Object newInstance(Class<?> type) {
                        return sagas.apply(journal);
                    }
                })
                .clock(Clock.fixed(NOW, ZoneOffset.UTC))
                .sagaIds(() -> "saga-" + sagaIds.incrementAndGet())
                .logger(logger)
                .build();
        built.start();
        return built;
    }

    /** A new engine over the same stores; the old one is no longer used. */
    public UnwindFixture restart() {
        engine = build(null);
        return this;
    }

    public SagaHandlingResult receive(Object event) {
        HandlingReport report = engine.receive(event);
        if (report.results().size() != 1) {
            throw new IllegalStateException("Expected one result, got " + report.results());
        }
        return report.results().get(0);
    }

    public String start(String orderId) {
        return receive(new UnwindStarted(orderId)).sagaId();
    }

    public SagaInstance committed(String sagaId) {
        return storage.committed().findById(sagaId).orElseThrow();
    }

    public Journal journal() {
        return journal;
    }

    public TransactionalInMemoryStorageProvider storage() {
        return storage;
    }

    public RecordingLogger logger() {
        return logger;
    }
}
