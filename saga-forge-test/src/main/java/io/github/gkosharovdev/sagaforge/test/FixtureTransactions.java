package io.github.gkosharovdev.sagaforge.test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import io.github.gkosharovdev.sagaforge.core.spi.LocalTransaction;
import io.github.gkosharovdev.sagaforge.core.spi.TransactionBoundary;

/**
 * The fixture's local transactions: one per thread, never nested. Participants are snapshotted at {@code begin()}
 * and restored on rollback; commit and rollback callbacks let the fixture keep what a committed unit of work did and
 * drop what a rolled-back attempt did (research §3, §6).
 */
final class FixtureTransactions implements TransactionBoundary {

    /** State that rolls back with a transaction. */
    interface Participant {

        Object snapshot();

        void restore(Object snapshot);
    }

    private final List<Participant> participants = new CopyOnWriteArrayList<>();
    private final ThreadLocal<Transaction> active = new ThreadLocal<>();

    void participant(Participant participant) {
        participants.add(participant);
    }

    @Override
    public LocalTransaction begin() {
        if (active.get() != null) {
            throw new IllegalStateException("a transaction is already active on this thread; the saga test fixture"
                    + " never suspends one");
        }
        List<Object> snapshots = new ArrayList<>();
        for (Participant participant : participants) {
            snapshots.add(participant.snapshot());
        }
        Transaction transaction = new Transaction(snapshots);
        active.set(transaction);
        return transaction;
    }

    @Override
    public Optional<LocalTransaction> current() {
        return Optional.ofNullable(active.get());
    }

    /** The transaction active on this thread, with its callbacks. */
    Optional<Transaction> active() {
        return Optional.ofNullable(active.get());
    }

    final class Transaction implements LocalTransaction {

        private final List<Object> snapshots;
        private final List<Runnable> onCommit = new ArrayList<>();
        private final List<Runnable> onRollback = new ArrayList<>();
        private boolean rollbackOnly;
        private boolean ended;

        private Transaction(List<Object> snapshots) {
            this.snapshots = snapshots;
        }

        void onCommit(Runnable callback) {
            onCommit.add(callback);
        }

        void onRollback(Runnable callback) {
            onRollback.add(callback);
        }

        @Override
        public void commit() {
            requireActive();
            if (rollbackOnly) {
                rollBack();
                throw new IllegalStateException("the transaction is rollback-only; it was rolled back");
            }
            end();
            onCommit.forEach(Runnable::run);
        }

        @Override
        public void rollback() {
            requireActive();
            rollBack();
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
            return Optional.empty();
        }

        private void rollBack() {
            for (int i = participants.size() - 1; i >= 0; i--) {
                participants.get(i).restore(snapshots.get(i));
            }
            end();
            onRollback.forEach(Runnable::run);
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
