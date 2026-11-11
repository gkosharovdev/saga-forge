package io.github.gkosharovdev.sagaforge.runtime.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.Snapshottable;
import io.github.gkosharovdev.sagaforge.core.spi.LocalTransaction;
import io.github.gkosharovdev.sagaforge.core.spi.TransactionBoundary;

/**
 * Thread-bound in-memory {@link TransactionBoundary}, standing in for the host's local transaction in engine tests.
 *
 * <p><strong>Test-only scaffolding.</strong> This class lives in {@code src/test} and must never move to
 * {@code src/main} (Constitution Principle IV).</p>
 *
 * <ul>
 *   <li>{@link #begin()} throws {@link IllegalStateException} if a transaction is already active on the thread (it
 *       never suspends).</li>
 *   <li>Each transaction exposes a per-transaction {@link Scope} as {@code resource(Scope.class)}, through which test
 *       stores join it: they register {@link Scope#onCommit}, {@link Scope#onRollback} and {@link Scope#onEnd} hooks,
 *       the same {@code resource(Class)} path a JDBC store uses with {@code Connection}.</li>
 *   <li>{@linkplain #participant(Snapshottable) Participants} (fixture repositories and services) are snapshotted at
 *       {@code begin} and restored at {@code rollback}, standing in for the host's transaction over client storage.
 *       A participant has no isolation: concurrent transactions see each other's uncommitted changes, and a rollback
 *       restores the whole participant.</li>
 *   <li>{@link #failNextCommit(RuntimeException)} makes the next commit roll back and then throw.</li>
 * </ul>
 *
 * <p>On commit the transaction is ended and unbound first, then the {@code onCommit} hooks run, then the
 * {@code onEnd} hooks; on rollback the participants are restored, the transaction is ended and unbound, then the
 * {@code onRollback} and {@code onEnd} hooks run. Hooks run on the transaction's thread and must not throw.</p>
 *
 * <p>Thread-safe: transactions are confined to the thread that began them; counters, history and fault injection
 * may be read and set from any thread.</p>
 */
public final class InMemoryTransactionBoundary implements TransactionBoundary {

    /** What happened to a transaction, as recorded in {@link #historyOf(Thread)}. */
    public enum TransactionEvent {
        /** A transaction began. */
        BEGIN,
        /** A transaction committed. */
        COMMIT,
        /** A transaction rolled back (explicitly, rollback-only at commit, or after an injected commit failure). */
        ROLLBACK
    }

    private final ThreadLocal<Transaction> active = new ThreadLocal<>();
    private final List<Snapshottable> participants = new CopyOnWriteArrayList<>();
    private final Map<Thread, List<TransactionEvent>> histories = new ConcurrentHashMap<>();
    private final AtomicInteger begins = new AtomicInteger();
    private final AtomicInteger commits = new AtomicInteger();
    private final AtomicInteger rollbacks = new AtomicInteger();
    private final AtomicReference<RuntimeException> nextCommitFailure = new AtomicReference<>();

    @Override
    public LocalTransaction begin() {
        if (active.get() != null) {
            throw new IllegalStateException("a transaction is already active on thread "
                    + Thread.currentThread().getName() + "; InMemoryTransactionBoundary never suspends");
        }
        Transaction transaction = new Transaction(snapshotParticipants());
        active.set(transaction);
        begins.incrementAndGet();
        recordEvent(TransactionEvent.BEGIN);
        return transaction;
    }

    @Override
    public Optional<LocalTransaction> current() {
        return Optional.ofNullable(active.get());
    }

    /**
     * Registers an object whose state every transaction snapshots at {@code begin} and restores at
     * {@code rollback}.
     *
     * @param participant the fixture object; must not be {@code null}
     * @return this boundary
     * @throws IllegalArgumentException if {@code participant} is {@code null}
     */
    public InMemoryTransactionBoundary participant(Snapshottable participant) {
        if (participant == null) {
            throw new IllegalArgumentException("participant must not be null");
        }
        participants.add(participant);
        return this;
    }

    /**
     * Makes the next commit, on any thread, roll the transaction back and then throw {@code failure}.
     *
     * @param failure the exception to throw; must not be {@code null}
     * @throws IllegalArgumentException if {@code failure} is {@code null}
     */
    public void failNextCommit(RuntimeException failure) {
        if (failure == null) {
            throw new IllegalArgumentException("failure must not be null");
        }
        nextCommitFailure.set(failure);
    }

    /** @return the number of transactions begun */
    public int begins() {
        return begins.get();
    }

    /** @return the number of transactions committed */
    public int commits() {
        return commits.get();
    }

    /** @return the number of transactions rolled back */
    public int rollbacks() {
        return rollbacks.get();
    }

    /**
     * Returns what happened to the transactions of one thread, in order.
     *
     * @param thread the thread; must not be {@code null}
     * @return an unmodifiable copy; empty if the thread began no transaction
     * @throws IllegalArgumentException if {@code thread} is {@code null}
     */
    public List<TransactionEvent> historyOf(Thread thread) {
        if (thread == null) {
            throw new IllegalArgumentException("thread must not be null");
        }
        List<TransactionEvent> history = histories.getOrDefault(thread, List.of());
        synchronized (history) {
            return List.copyOf(history);
        }
    }

    private List<ParticipantSnapshot> snapshotParticipants() {
        List<ParticipantSnapshot> snapshots = new ArrayList<>();
        for (Snapshottable participant : participants) {
            snapshots.add(new ParticipantSnapshot(participant, participant.snapshot()));
        }
        return List.copyOf(snapshots);
    }

    private void recordEvent(TransactionEvent event) {
        List<TransactionEvent> history =
                histories.computeIfAbsent(Thread.currentThread(), thread -> new ArrayList<>());
        synchronized (history) {
            history.add(event);
        }
    }

    /**
     * The per-transaction hooks through which test stores join a transaction; obtained with
     * {@code transaction.resource(InMemoryTransactionBoundary.Scope.class)}.
     *
     * <p>Thread-safe registration; the hooks run on the transaction's thread when it ends.</p>
     */
    public static final class Scope {

        private final List<Runnable> commitHooks = new CopyOnWriteArrayList<>();
        private final List<Runnable> rollbackHooks = new CopyOnWriteArrayList<>();
        private final List<Runnable> endHooks = new CopyOnWriteArrayList<>();

        private Scope() {
        }

        /**
         * Runs {@code hook} when the transaction commits, before the {@link #onEnd} hooks.
         *
         * @param hook the hook; must not be {@code null}
         * @throws IllegalArgumentException if {@code hook} is {@code null}
         */
        public void onCommit(Runnable hook) {
            commitHooks.add(requireHook(hook));
        }

        /**
         * Runs {@code hook} when the transaction rolls back, before the {@link #onEnd} hooks.
         *
         * @param hook the hook; must not be {@code null}
         * @throws IllegalArgumentException if {@code hook} is {@code null}
         */
        public void onRollback(Runnable hook) {
            rollbackHooks.add(requireHook(hook));
        }

        /**
         * Runs {@code hook} when the transaction ends, whether it committed or rolled back.
         *
         * @param hook the hook; must not be {@code null}
         * @throws IllegalArgumentException if {@code hook} is {@code null}
         */
        public void onEnd(Runnable hook) {
            endHooks.add(requireHook(hook));
        }

        private void committed() {
            commitHooks.forEach(Runnable::run);
            endHooks.forEach(Runnable::run);
        }

        private void rolledBack() {
            rollbackHooks.forEach(Runnable::run);
            endHooks.forEach(Runnable::run);
        }

        private static Runnable requireHook(Runnable hook) {
            if (hook == null) {
                throw new IllegalArgumentException("hook must not be null");
            }
            return hook;
        }
    }

    private record ParticipantSnapshot(Snapshottable participant, Object snapshot) {

        void restore() {
            participant.restore(snapshot);
        }
    }

    /** Confined to the thread that began it. */
    private final class Transaction implements LocalTransaction {

        private final Scope scope = new Scope();
        private final List<ParticipantSnapshot> snapshots;
        private boolean rollbackOnly;
        private boolean ended;

        private Transaction(List<ParticipantSnapshot> snapshots) {
            this.snapshots = snapshots;
        }

        @Override
        public void commit() {
            requireActive();
            if (rollbackOnly) {
                rollBackAndEnd();
                throw new IllegalStateException("the transaction is rollback-only; it was rolled back");
            }
            RuntimeException injected = nextCommitFailure.getAndSet(null);
            if (injected != null) {
                rollBackAndEnd();
                throw injected;
            }
            end();
            commits.incrementAndGet();
            recordEvent(TransactionEvent.COMMIT);
            scope.committed();
        }

        @Override
        public void rollback() {
            requireActive();
            rollBackAndEnd();
        }

        @Override
        public void setRollbackOnly() {
            requireActive();
            rollbackOnly = true;
        }

        @Override
        public boolean isRollbackOnly() {
            return rollbackOnly;
        }

        @Override
        public <R> Optional<R> resource(Class<R> type) {
            if (type == null) {
                throw new IllegalArgumentException("type must not be null");
            }
            requireActive();
            return type == Scope.class ? Optional.of(type.cast(scope)) : Optional.empty();
        }

        private void rollBackAndEnd() {
            for (int i = snapshots.size() - 1; i >= 0; i--) {
                snapshots.get(i).restore();
            }
            end();
            rollbacks.incrementAndGet();
            recordEvent(TransactionEvent.ROLLBACK);
            scope.rolledBack();
        }

        private void end() {
            ended = true;
            active.remove();
        }

        private void requireActive() {
            if (ended) {
                throw new IllegalStateException("the transaction has already ended");
            }
        }
    }
}
