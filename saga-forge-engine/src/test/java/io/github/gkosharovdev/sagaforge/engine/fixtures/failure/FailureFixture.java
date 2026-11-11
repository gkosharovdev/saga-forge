package io.github.gkosharovdev.sagaforge.engine.fixtures.failure;

import io.github.gkosharovdev.sagaforge.core.execution.harness.InMemorySagaContextStore;
import io.github.gkosharovdev.sagaforge.core.execution.harness.MapHandlerInstanceProvider;
import io.github.gkosharovdev.sagaforge.core.execution.harness.RecordingEventDispatcher;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingReport;
import io.github.gkosharovdev.sagaforge.core.spi.SagaContextRecord;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.engine.SagaEngine;
import io.github.gkosharovdev.sagaforge.engine.support.CapturingOutbox;
import io.github.gkosharovdev.sagaforge.engine.support.InMemoryTransactionBoundary;
import io.github.gkosharovdev.sagaforge.engine.support.RecordingLogger;
import io.github.gkosharovdev.sagaforge.engine.support.TransactionalInMemoryStorageProvider;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A started engine running {@link FailureSaga} over the engine test doubles, with every client object a participant
 * of the {@link InMemoryTransactionBoundary} so a rollback undoes its changes (specs/004-saga-engine tasks.md T056).
 *
 * <p>The outbox is a {@link CapturingOutbox} ({@code AFTER_COMMIT}); diagnostics go to a
 * {@link RecordingLogger}. Saga ids are {@code saga-1}, {@code saga-2}, ...; the clock is fixed.</p>
 *
 * <p>Test-only. Confined to the test's thread.</p>
 */
public final class FailureFixture {

    /** The instant every timestamp carries. */
    public static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    /** Cents per kilogram charged by {@link FeeService}. */
    public static final long CENTS_PER_KILOGRAM = 150;

    private final FaultPlan plan = new FaultPlan();
    private final FeeService fees = new FeeService(plan);
    private final SlotRepository slots = new SlotRepository(plan);
    private final LabelResolver labels = new LabelResolver(plan);
    private final InMemoryTransactionBoundary boundary = new InMemoryTransactionBoundary()
            .participant(fees).participant(slots).participant(labels);
    private final TransactionalInMemoryStorageProvider storage = new TransactionalInMemoryStorageProvider(boundary);
    private final InMemorySagaContextStore contextStore = new InMemorySagaContextStore();
    private final RecordingEventDispatcher dispatcher = new RecordingEventDispatcher();
    private final CapturingOutbox outbox = CapturingOutbox.afterCommit();
    private final RecordingLogger logger = new RecordingLogger();
    private final SagaEngine engine;

    /** Assembles and starts the engine. */
    public FailureFixture() {
        AtomicInteger sagaIds = new AtomicInteger();
        MapHandlerInstanceProvider instances = new MapHandlerInstanceProvider().register(fees).register(slots);
        engine = SagaEngine.builder()
                .register(FailureSaga.class, FeeService.class, Slot.class, SlotRepository.class, Label.class,
                        Parcel.class, Fee.class)
                .storage(storage)
                .contextStore(contextStore)
                .transactions(boundary)
                .dispatcher(dispatcher)
                .outbox(outbox)
                .handlerInstances(new FailingHandlerInstances(plan, instances))
                .aggregateResolver(labels)
                .parameterResolver(new FeeScheduleResolver(plan, new FeeSchedule(CENTS_PER_KILOGRAM)))
                .sagaObjects(new FailureSagaObjects(plan))
                .clock(Clock.fixed(NOW, ZoneOffset.UTC))
                .sagaIds(() -> "saga-" + sagaIds.incrementAndGet())
                .logger(logger)
                .build();
        engine.start();
    }

    /** Seeds the parcel's slot and label, then receives its {@link ParcelRequested}. */
    public HandlingReport requestParcel(String parcelId, long weight) {
        slots.add(Slot.slotIdFor(parcelId));
        labels.add(Label.labelIdFor(parcelId));
        return engine.receive(new ParcelRequested(parcelId, weight));
    }

    /** The committed instance {@code sagaId}. */
    public SagaInstance committed(String sagaId) {
        return storage.committed().findById(sagaId).orElseThrow();
    }

    /** The stored {@code SagaContext} values of {@code sagaId}; empty if none. */
    public Map<String, Object> context(String sagaId) {
        return contextStore.find(sagaId).map(SagaContextRecord::values).orElse(Map.of());
    }

    /** {@code true} if no fee, slot reservation or printed label is held. */
    public boolean noClientChanges() {
        return fees.fees().isEmpty() && slots.reservations().isEmpty() && labels.printed().isEmpty();
    }

    public FaultPlan plan() {
        return plan;
    }

    public FeeService fees() {
        return fees;
    }

    public SlotRepository slots() {
        return slots;
    }

    public LabelResolver labels() {
        return labels;
    }

    public InMemoryTransactionBoundary boundary() {
        return boundary;
    }

    public TransactionalInMemoryStorageProvider storage() {
        return storage;
    }

    public CapturingOutbox outbox() {
        return outbox;
    }

    public RecordingEventDispatcher dispatcher() {
        return dispatcher;
    }

    public RecordingLogger logger() {
        return logger;
    }

    public SagaEngine engine() {
        return engine;
    }
}
