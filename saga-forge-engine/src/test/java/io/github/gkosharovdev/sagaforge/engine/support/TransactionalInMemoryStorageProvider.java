package io.github.gkosharovdev.sagaforge.engine.support;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import io.github.gkosharovdev.sagaforge.core.spi.SagaConcurrencyConflictException;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaLifecycleEntry;
import io.github.gkosharovdev.sagaforge.core.spi.StorageProvider;
import io.github.gkosharovdev.sagaforge.engine.support.InMemoryTransactionBoundary.Scope;

/**
 * In-memory {@link StorageProvider} that joins the transactions of an {@link InMemoryTransactionBoundary}, standing in
 * for a transactional database in engine tests.
 *
 * <p><strong>Test-only scaffolding.</strong> This class lives in {@code src/test} and must never move to
 * {@code src/main} (Constitution Principle IV).</p>
 *
 * <ul>
 *   <li><strong>Joined</strong>: inside an active transaction every write is buffered in that transaction and applied
 *       when it commits, discarded when it rolls back, so other threads see committed state only. Reads in a
 *       transaction see its own buffered writes.</li>
 *   <li><strong>Own transaction</strong>: with no transaction active, writes apply immediately.</li>
 *   <li><strong>History</strong>: entries handed to {@link #save(SagaInstance, List)} are numbered by the store when
 *       the write is applied, consecutively after the instance's committed {@link #history(String)}.</li>
 *   <li><strong>Locking</strong>: {@link #findByIdAndLock(String)} takes a fair per-{@code sagaId} lock owned by the
 *       active transaction, re-entrant within it, and released when it ends. Two engines sharing one instance of
 *       this class model two processes sharing a database.</li>
 *   <li><strong>Fault injection</strong>: {@link #failNextSave(RuntimeException)},
 *       {@link #conflictOnNextSave(String)} and {@link #failNextFindByIdAndLock(RuntimeException)}.</li>
 * </ul>
 *
 * <p>Instances are kept in insertion order, so {@link #findByAssociation(String, String)} is deterministic.
 * Thread-safe.</p>
 */
public final class TransactionalInMemoryStorageProvider implements StorageProvider {

    /**
     * A lifecycle entry with the sequence number the store assigned to it.
     *
     * @param sequenceNumber the entry's position in its instance's history, from 0
     * @param entry          the entry as handed over
     */
    public record StoredEntry(long sequenceNumber, SagaLifecycleEntry entry) {
    }

    private final InMemoryTransactionBoundary boundary;
    private final CommittedState committedState = new CommittedState();
    private final Map<Scope, PendingWrites> pending = new ConcurrentHashMap<>();
    private final Map<Scope, Set<String>> heldLocks = new ConcurrentHashMap<>();
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final AtomicReference<RuntimeException> nextSaveFailure = new AtomicReference<>();
    private final AtomicReference<String> nextConflictSagaId = new AtomicReference<>();
    private final AtomicReference<RuntimeException> nextLockFailure = new AtomicReference<>();
    private final AtomicInteger saveCalls = new AtomicInteger();

    /**
     * @param boundary the boundary whose transactions this store joins; must not be {@code null}
     * @throws IllegalArgumentException if {@code boundary} is {@code null}
     */
    public TransactionalInMemoryStorageProvider(InMemoryTransactionBoundary boundary) {
        if (boundary == null) {
            throw new IllegalArgumentException("boundary must not be null");
        }
        this.boundary = boundary;
    }

    @Override
    public void save(SagaInstance instance, List<SagaLifecycleEntry> newEntries) {
        requireEntriesOf(instance, newEntries);
        throwInjectedSaveFault(instance.sagaId());
        Write write = new Write(instance, List.copyOf(newEntries));
        record(write);
        saveCalls.incrementAndGet();
    }

    @Override
    public Optional<SagaInstance> findById(String sagaId) {
        requireSagaId(sagaId);
        return activeScope()
                .map(scope -> viewOf(scope).findById(sagaId))
                .orElseGet(() -> committedState.findById(sagaId));
    }

    @Override
    public Optional<SagaInstance> findByIdAndLock(String sagaId) {
        requireSagaId(sagaId);
        Scope scope = activeScope().orElseThrow(() -> new IllegalStateException(
                "findByIdAndLock requires an active InMemoryTransactionBoundary transaction"));
        RuntimeException injected = nextLockFailure.getAndSet(null);
        if (injected != null) {
            throw injected;
        }
        lock(scope, sagaId);
        return viewOf(scope).findById(sagaId);
    }

    @Override
    public List<SagaInstance> findByAssociation(String associationProperty, String associationValue) {
        if (associationProperty == null) {
            throw new IllegalArgumentException("associationProperty must not be null");
        }
        if (associationValue == null) {
            throw new IllegalArgumentException("associationValue must not be null");
        }
        List<SagaInstance> visible = activeScope().map(scope -> viewOf(scope).all()).orElseGet(committedState::all);
        return visible.stream()
                .filter(instance -> associationValue.equals(instance.associations().get(associationProperty)))
                .toList();
    }

    @Override
    public void delete(String sagaId) {
        requireSagaId(sagaId);
        record(Write.deletion(sagaId));
    }

    /**
     * Returns the committed lifecycle history of an instance, in stored order.
     *
     * @param sagaId the saga instance identifier; must not be {@code null}
     * @return an unmodifiable list; empty if none was committed
     * @throws IllegalArgumentException if {@code sagaId} is {@code null}
     */
    public List<StoredEntry> history(String sagaId) {
        requireSagaId(sagaId);
        return committedState.history(sagaId);
    }

    /**
     * Returns a read-only view of the committed state, ignoring any transaction active on the calling thread; for
     * observers such as a probe reading the store from inside a step.
     *
     * @return the committed-only view; never {@code null}
     */
    public CommittedView committed() {
        return new CommittedView(committedState);
    }

    /** @return the number of accepted {@code save} calls (buffered or applied); rejected and failed calls excluded */
    public int saveCalls() {
        return saveCalls.get();
    }

    /**
     * Makes the next {@code save}, on any thread, throw {@code failure} before anything is written.
     *
     * @param failure the exception to throw; must not be {@code null}
     * @throws IllegalArgumentException if {@code failure} is {@code null}
     */
    public void failNextSave(RuntimeException failure) {
        nextSaveFailure.set(requireFailure(failure));
    }

    /**
     * Makes the next {@code save} of {@code sagaId} throw {@link SagaConcurrencyConflictException} before anything
     * is written.
     *
     * @param sagaId the instance whose next write conflicts; must not be {@code null}
     * @throws IllegalArgumentException if {@code sagaId} is {@code null}
     */
    public void conflictOnNextSave(String sagaId) {
        requireSagaId(sagaId);
        nextConflictSagaId.set(sagaId);
    }

    /**
     * Makes the next {@code findByIdAndLock}, on any thread, throw {@code failure} before locking.
     *
     * @param failure the exception to throw; must not be {@code null}
     * @throws IllegalArgumentException if {@code failure} is {@code null}
     */
    public void failNextFindByIdAndLock(RuntimeException failure) {
        nextLockFailure.set(requireFailure(failure));
    }

    private Optional<Scope> activeScope() {
        return boundary.current().map(transaction -> transaction.resource(Scope.class)
                .orElseThrow(() -> new IllegalStateException(
                        "the active transaction has no resource of type " + Scope.class.getName())));
    }

    /** Buffers {@code write} in the active transaction, or applies it at once when none is active. */
    private void record(Write write) {
        Optional<Scope> scope = activeScope();
        if (scope.isPresent()) {
            pendingFor(scope.get()).add(write);
        } else {
            committedState.apply(List.of(write));
        }
    }

    private PendingWrites pendingFor(Scope scope) {
        return pending.computeIfAbsent(scope, joined -> {
            PendingWrites writes = new PendingWrites();
            joined.onCommit(() -> committedState.apply(writes.writes()));
            joined.onEnd(() -> pending.remove(joined));
            return writes;
        });
    }

    private TransactionView viewOf(Scope scope) {
        return new TransactionView(committedState, pending.get(scope));
    }

    private void lock(Scope scope, String sagaId) {
        Set<String> held = heldLocks.computeIfAbsent(scope, joined -> {
            Set<String> sagaIds = ConcurrentHashMap.newKeySet();
            joined.onEnd(() -> release(joined));
            return sagaIds;
        });
        if (held.contains(sagaId)) {
            return;
        }
        ReentrantLock lock = locks.computeIfAbsent(sagaId, id -> new ReentrantLock(true));
        try {
            lock.lockInterruptibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the lock on saga " + sagaId, e);
        }
        held.add(sagaId);
    }

    private void release(Scope scope) {
        Set<String> held = heldLocks.remove(scope);
        if (held != null) {
            held.forEach(sagaId -> locks.get(sagaId).unlock());
        }
    }

    private void throwInjectedSaveFault(String sagaId) {
        RuntimeException injected = nextSaveFailure.getAndSet(null);
        if (injected != null) {
            throw injected;
        }
        if (nextConflictSagaId.compareAndSet(sagaId, null)) {
            throw new SagaConcurrencyConflictException(sagaId, "injected write-time conflict for saga " + sagaId);
        }
    }

    private static void requireEntriesOf(SagaInstance instance, List<SagaLifecycleEntry> newEntries) {
        if (instance == null) {
            throw new IllegalArgumentException("instance must not be null");
        }
        if (newEntries == null) {
            throw new IllegalArgumentException("newEntries must not be null");
        }
        for (SagaLifecycleEntry entry : newEntries) {
            if (entry == null) {
                throw new IllegalArgumentException("newEntries must not contain null elements");
            }
            if (!entry.sagaId().equals(instance.sagaId())) {
                throw new IllegalArgumentException("lifecycle entry of saga " + entry.sagaId()
                        + " cannot be saved with saga instance " + instance.sagaId());
            }
        }
    }

    private static void requireSagaId(String sagaId) {
        if (sagaId == null) {
            throw new IllegalArgumentException("sagaId must not be null");
        }
    }

    private static RuntimeException requireFailure(RuntimeException failure) {
        if (failure == null) {
            throw new IllegalArgumentException("failure must not be null");
        }
        return failure;
    }

    /**
     * Read-only view of the committed state of a {@link TransactionalInMemoryStorageProvider}. Thread-safe.
     */
    public static final class CommittedView {

        private final CommittedState state;

        private CommittedView(CommittedState state) {
            this.state = state;
        }

        /**
         * @param sagaId the saga instance identifier; must not be {@code null}
         * @return the committed instance, or empty
         * @throws IllegalArgumentException if {@code sagaId} is {@code null}
         */
        public Optional<SagaInstance> findById(String sagaId) {
            requireSagaId(sagaId);
            return state.findById(sagaId);
        }

        /** @return every committed instance, in insertion order; unmodifiable */
        public List<SagaInstance> all() {
            return state.all();
        }
    }

    /** One buffered or applied write: an instance with its entries, or a deletion. */
    private record Write(String sagaId, SagaInstance instance, List<SagaLifecycleEntry> entries) {

        Write(SagaInstance instance, List<SagaLifecycleEntry> entries) {
            this(instance.sagaId(), instance, entries);
        }

        static Write deletion(String sagaId) {
            return new Write(sagaId, null, List.of());
        }

        boolean isDeletion() {
            return instance == null;
        }
    }

    /** The writes one transaction buffered, in order. Confined to the transaction's thread. */
    private static final class PendingWrites {

        private final List<Write> writes = new ArrayList<>();

        void add(Write write) {
            writes.add(write);
        }

        List<Write> writes() {
            return List.copyOf(writes);
        }
    }

    /** What a transaction sees: the committed state with its own buffered writes applied on top. */
    private record TransactionView(CommittedState committed, PendingWrites ownWrites) {

        Optional<SagaInstance> findById(String sagaId) {
            return Optional.ofNullable(visible().get(sagaId));
        }

        List<SagaInstance> all() {
            return List.copyOf(visible().values());
        }

        private Map<String, SagaInstance> visible() {
            Map<String, SagaInstance> visible = committed.snapshot();
            if (ownWrites != null) {
                ownWrites.writes().forEach(write -> applyTo(visible, write));
            }
            return visible;
        }

        private static void applyTo(Map<String, SagaInstance> instances, Write write) {
            if (write.isDeletion()) {
                instances.remove(write.sagaId());
            } else {
                instances.put(write.sagaId(), write.instance());
            }
        }
    }

    /** The committed instances and histories, guarded by this object's monitor. */
    private static final class CommittedState {

        private final Map<String, SagaInstance> instances = new LinkedHashMap<>();
        private final Map<String, List<StoredEntry>> histories = new HashMap<>();

        synchronized void apply(List<Write> writes) {
            writes.forEach(this::applyOne);
        }

        synchronized Optional<SagaInstance> findById(String sagaId) {
            return Optional.ofNullable(instances.get(sagaId));
        }

        synchronized List<SagaInstance> all() {
            return List.copyOf(instances.values());
        }

        synchronized Map<String, SagaInstance> snapshot() {
            return new LinkedHashMap<>(instances);
        }

        synchronized List<StoredEntry> history(String sagaId) {
            return List.copyOf(histories.getOrDefault(sagaId, List.of()));
        }

        private void applyOne(Write write) {
            if (write.isDeletion()) {
                instances.remove(write.sagaId());
                histories.remove(write.sagaId());
                return;
            }
            instances.put(write.sagaId(), write.instance());
            List<StoredEntry> history = histories.computeIfAbsent(write.sagaId(), sagaId -> new ArrayList<>());
            for (SagaLifecycleEntry entry : write.entries()) {
                history.add(new StoredEntry(history.size(), entry));
            }
        }
    }
}
