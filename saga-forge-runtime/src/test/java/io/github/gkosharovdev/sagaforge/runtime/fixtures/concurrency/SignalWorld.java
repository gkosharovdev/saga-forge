package io.github.gkosharovdev.sagaforge.runtime.fixtures.concurrency;

import io.github.gkosharovdev.sagaforge.core.execution.harness.RecordingEventDispatcher;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaLifecycleEntry;
import io.github.gkosharovdev.sagaforge.runtime.SagaRuntime;
import io.github.gkosharovdev.sagaforge.runtime.support.ConcurrentSagaContextStore;
import io.github.gkosharovdev.sagaforge.runtime.support.InMemoryTransactionBoundary;
import io.github.gkosharovdev.sagaforge.runtime.support.RecordingLogger;
import io.github.gkosharovdev.sagaforge.runtime.support.TransactionalInMemoryStorageProvider;
import io.github.gkosharovdev.sagaforge.runtime.support.TransactionalInMemoryStorageProvider.StoredEntry;
import java.util.List;

/**
 * Stores shared by every engine that runs {@link SignalSaga}: a {@link TransactionalInMemoryStorageProvider} with
 * row-lock semantics and a thread-safe context store. Each {@link #engine()} is a fresh engine over them, so two
 * engines model two processes sharing a database.
 *
 * <p>Test-only. Thread-safe.</p>
 */
public final class SignalWorld {

    private final InMemoryTransactionBoundary boundary = new InMemoryTransactionBoundary();
    private final TransactionalInMemoryStorageProvider storage = new TransactionalInMemoryStorageProvider(boundary);
    private final ConcurrentSagaContextStore contextStore = new ConcurrentSagaContextStore();
    private final RecordingLogger logger = new RecordingLogger();
    private final StepProbe probe = new StepProbe();

    /** A new started engine over this world's stores. */
    public SagaRuntime engine() {
        SagaRuntime engine = SagaRuntime.builder()
                .register(SignalSaga.class, Ticket.class)
                .storage(storage)
                .contextStore(contextStore)
                .transactions(boundary)
                .dispatcher(new RecordingEventDispatcher())
                .sagaObjects(new SignalSagaObjects(probe))
                .logger(logger)
                .build();
        engine.start();
        return engine;
    }

    /** The committed instance {@code sagaId}. */
    public SagaInstance committed(String sagaId) {
        return storage.committed().findById(sagaId).orElseThrow();
    }

    /** The committed history entries of {@code sagaId}, in stored order. */
    public List<SagaLifecycleEntry> history(String sagaId) {
        return storage.history(sagaId).stream().map(StoredEntry::entry).toList();
    }

    public TransactionalInMemoryStorageProvider storage() {
        return storage;
    }

    public ConcurrentSagaContextStore contextStore() {
        return contextStore;
    }

    public RecordingLogger logger() {
        return logger;
    }

    public StepProbe probe() {
        return probe;
    }
}
