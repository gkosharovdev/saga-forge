package io.github.gkosharovdev.sagaforge.engine.support;

import java.lang.System.Logger.Level;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.Snapshottable;
import io.github.gkosharovdev.sagaforge.core.spi.Outbox;
import io.github.gkosharovdev.sagaforge.core.spi.OutgoingMessages;
import io.github.gkosharovdev.sagaforge.core.spi.LocalTransaction;
import io.github.gkosharovdev.sagaforge.core.spi.SagaConcurrencyConflictException;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaLifecycleEntry;
import io.github.gkosharovdev.sagaforge.core.spi.SagaLifecycleEventKind;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.engine.support.InMemoryTransactionBoundary.Scope;
import io.github.gkosharovdev.sagaforge.engine.support.InMemoryTransactionBoundary.TransactionEvent;

/**
 * The engine test doubles behave as the engine tests rely on (specs/004-saga-engine tasks.md T024 to T026).
 */
class TestDoublesTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final long WAIT_MILLIS = 200;
    private static final long TIMEOUT_SECONDS = 5;

    private final InMemoryTransactionBoundary boundary = new InMemoryTransactionBoundary();

    private static SagaInstance instance(String sagaId, SagaStatus status) {
        return new SagaInstance(sagaId, "com.example.OrderSaga", Map.of(), Map.of("orderId", "o-" + sagaId), status,
                NOW, NOW, Map.of(), null, null);
    }

    private static SagaLifecycleEntry created(String sagaId) {
        return new SagaLifecycleEntry(sagaId, SagaLifecycleEventKind.CREATED, null, List.of(), null, null, NOW);
    }

    private static SagaLifecycleEntry stepCompleted(String sagaId, String step) {
        return new SagaLifecycleEntry(sagaId, SagaLifecycleEventKind.STEP_COMPLETED, step, List.of(), null, null,
                NOW);
    }

    /** A participant holding one number, standing in for a fixture repository. */
    private static final class Counter implements Snapshottable {

        private int value;

        @Override
        public Object snapshot() {
            return value;
        }

        @Override
        public void restore(Object snapshot) {
            value = (Integer) snapshot;
        }
    }

    @Nested
    @DisplayName("InMemoryTransactionBoundary")
    class Boundary {

        @Test
        @DisplayName("begin binds a transaction to the thread until commit; counts and history are recorded")
        void beginAndCommit() {
            LocalTransaction transaction = boundary.begin();

            assertThat(boundary.current()).containsSame(transaction);
            transaction.commit();

            assertThat(boundary.isActive()).isFalse();
            assertThat(boundary.begins()).isEqualTo(1);
            assertThat(boundary.commits()).isEqualTo(1);
            assertThat(boundary.historyOf(Thread.currentThread()))
                    .containsExactly(TransactionEvent.BEGIN, TransactionEvent.COMMIT);
        }

        @Test
        @DisplayName("a second begin on the same thread is rejected")
        void secondBeginRejected() {
            LocalTransaction transaction = boundary.begin();

            assertThatThrownBy(boundary::begin).isInstanceOf(IllegalStateException.class);
            transaction.rollback();
            assertThat(boundary.rollbacks()).isEqualTo(1);
        }

        @Test
        @DisplayName("an ended transaction rejects commit, rollback, setRollbackOnly and resource")
        void endedTransactionRejectsEverything() {
            LocalTransaction transaction = boundary.begin();
            transaction.commit();

            assertThatThrownBy(transaction::commit).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(transaction::rollback).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(transaction::setRollbackOnly).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> transaction.resource(Scope.class)).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("commit on a rollback-only transaction rolls back and throws")
        void rollbackOnlyCommitRollsBack() {
            Counter counter = new Counter();
            boundary.participant(counter);
            LocalTransaction transaction = boundary.begin();
            counter.value = 5;
            transaction.setRollbackOnly();

            assertThat(transaction.isRollbackOnly()).isTrue();
            assertThatThrownBy(transaction::commit).isInstanceOf(IllegalStateException.class);
            assertThat(counter.value).isZero();
            assertThat(boundary.isActive()).isFalse();
            assertThat(boundary.commits()).isZero();
            assertThat(boundary.rollbacks()).isEqualTo(1);
        }

        @Test
        @DisplayName("resource returns the Scope and nothing else; a null type is rejected")
        void resources() {
            LocalTransaction transaction = boundary.begin();

            assertThat(transaction.resource(Scope.class)).isPresent();
            assertThat(transaction.resource(String.class)).isEmpty();
            assertThatThrownBy(() -> transaction.resource(null)).isInstanceOf(IllegalArgumentException.class);
            transaction.rollback();
        }

        @Test
        @DisplayName("participants are restored on rollback and keep their changes on commit")
        void participantsSnapshotAndRestore() {
            Counter counter = new Counter();
            boundary.participant(counter);
            counter.value = 1;

            LocalTransaction rolledBack = boundary.begin();
            counter.value = 5;
            rolledBack.rollback();
            assertThat(counter.value).isEqualTo(1);

            LocalTransaction committed = boundary.begin();
            counter.value = 7;
            committed.commit();
            assertThat(counter.value).isEqualTo(7);
        }

        @Test
        @DisplayName("an injected commit failure rolls back, then throws")
        void injectedCommitFailure() {
            Counter counter = new Counter();
            boundary.participant(counter);
            RuntimeException failure = new IllegalStateException("disk full");
            boundary.failNextCommit(failure);
            LocalTransaction transaction = boundary.begin();
            counter.value = 3;

            assertThatThrownBy(transaction::commit).isSameAs(failure);
            assertThat(counter.value).isZero();
            assertThat(boundary.isActive()).isFalse();
            assertThat(boundary.historyOf(Thread.currentThread()))
                    .containsExactly(TransactionEvent.BEGIN, TransactionEvent.ROLLBACK);
        }
    }

    @Nested
    @DisplayName("TransactionalInMemoryStorageProvider")
    class Storage {

        private final TransactionalInMemoryStorageProvider storage = new TransactionalInMemoryStorageProvider(boundary);
        private ExecutorService otherThread;

        @BeforeEach
        void startOtherThread() {
            otherThread = Executors.newSingleThreadExecutor();
        }

        @AfterEach
        void stopOtherThread() throws InterruptedException {
            otherThread.shutdownNow();
            otherThread.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        private Optional<SagaInstance> readOnOtherThread(String sagaId) throws Exception {
            return otherThread.submit(() -> storage.findById(sagaId)).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        @Test
        @DisplayName("a write in a transaction is visible to it only, and to everyone after commit")
        void writeVisibleAfterCommit() throws Exception {
            LocalTransaction transaction = boundary.begin();
            storage.save(instance("s1", SagaStatus.CREATED), List.of(created("s1")));

            assertThat(storage.findById("s1")).isPresent();
            assertThat(storage.findByAssociation("orderId", "o-s1")).hasSize(1);
            assertThat(readOnOtherThread("s1")).isEmpty();
            assertThat(storage.committed().findById("s1")).isEmpty();
            assertThat(storage.history("s1")).isEmpty();

            transaction.commit();

            assertThat(readOnOtherThread("s1")).isPresent();
            assertThat(storage.history("s1")).extracting(TransactionalInMemoryStorageProvider.StoredEntry::entry)
                    .containsExactly(created("s1"));
            assertThat(storage.saveCalls()).isEqualTo(1);
        }

        @Test
        @DisplayName("a write in a rolled-back transaction is discarded")
        void writeDiscardedOnRollback() {
            LocalTransaction transaction = boundary.begin();
            storage.save(instance("s1", SagaStatus.CREATED), List.of(created("s1")));
            transaction.rollback();

            assertThat(storage.findById("s1")).isEmpty();
            assertThat(storage.history("s1")).isEmpty();
        }

        @Test
        @DisplayName("outside a transaction a write applies immediately")
        void ownTransactionWriteImmediate() throws Exception {
            storage.save(instance("s1", SagaStatus.CREATED), List.of(created("s1")));

            assertThat(readOnOtherThread("s1")).isPresent();
        }

        @Test
        @DisplayName("entries are numbered consecutively across two committed writes, in hand-over order")
        void entryNumberingAcrossWrites() {
            LocalTransaction first = boundary.begin();
            storage.save(instance("s1", SagaStatus.CREATED), List.of(created("s1")));
            first.commit();
            LocalTransaction second = boundary.begin();
            storage.save(instance("s1", SagaStatus.RUNNING), List.of(stepCompleted("s1", "zeta"),
                    stepCompleted("s1", "alpha")));
            second.commit();

            assertThat(storage.history("s1"))
                    .extracting(TransactionalInMemoryStorageProvider.StoredEntry::sequenceNumber)
                    .containsExactly(0L, 1L, 2L);
            assertThat(storage.history("s1")).extracting(stored -> stored.entry().stepName())
                    .containsExactly(null, "zeta", "alpha");
        }

        @Test
        @DisplayName("findByIdAndLock needs an active transaction")
        void lockNeedsTransaction() {
            assertThatThrownBy(() -> storage.findByIdAndLock("s1")).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> storage.findByIdAndLock(null)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a second transaction's findByIdAndLock blocks until the first ends, then reads its commit")
        void lockBlocksUntilTransactionEnds() throws Exception {
            storage.save(instance("s1", SagaStatus.CREATED), List.of(created("s1")));
            LocalTransaction holder = boundary.begin();
            assertThat(storage.findByIdAndLock("s1")).isPresent();
            assertThat(storage.findByIdAndLock("s1")).as("re-entrant within the transaction").isPresent();
            storage.save(instance("s1", SagaStatus.RUNNING), List.of());

            Future<Optional<SagaInstance>> waiter = otherThread.submit(() -> {
                LocalTransaction transaction = boundary.begin();
                try {
                    return storage.findByIdAndLock("s1");
                } finally {
                    transaction.commit();
                }
            });

            assertThatThrownBy(() -> waiter.get(WAIT_MILLIS, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            holder.commit();
            assertThat(waiter.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).get()
                    .extracting(SagaInstance::status).isEqualTo(SagaStatus.RUNNING);
        }

        @Test
        @DisplayName("an injected conflict throws SagaConcurrencyConflictException once, writing nothing")
        void injectedConflict() {
            storage.conflictOnNextSave("s1");

            assertThatThrownBy(() -> storage.save(instance("s1", SagaStatus.CREATED), List.of(created("s1"))))
                    .isInstanceOf(SagaConcurrencyConflictException.class)
                    .satisfies(e -> assertThat(((SagaConcurrencyConflictException) e).sagaId()).isEqualTo("s1"));
            assertThat(storage.findById("s1")).isEmpty();
            assertThat(storage.saveCalls()).isZero();

            storage.save(instance("s1", SagaStatus.CREATED), List.of(created("s1")));
            assertThat(storage.findById("s1")).isPresent();
        }

        @Test
        @DisplayName("injected save and lock failures are thrown once, writing nothing")
        void injectedFailures() {
            RuntimeException saveFailure = new IllegalStateException("store down");
            RuntimeException lockFailure = new IllegalStateException("lock wait aborted");
            storage.failNextSave(saveFailure);
            storage.failNextFindByIdAndLock(lockFailure);

            assertThatThrownBy(() -> storage.save(instance("s1", SagaStatus.CREATED), List.of()))
                    .isSameAs(saveFailure);
            assertThat(storage.findById("s1")).isEmpty();

            LocalTransaction transaction = boundary.begin();
            assertThatThrownBy(() -> storage.findByIdAndLock("s1")).isSameAs(lockFailure);
            assertThat(storage.findByIdAndLock("s1")).isEmpty();
            transaction.rollback();
        }
    }

    @Nested
    @DisplayName("CapturingOutbox")
    class CapturingOutboxDouble {

        private final OutgoingMessages first = new OutgoingMessages("uow-1", "com.example.OrderSaga", "s1",
                List.of("event-1"));
        private final OutgoingMessages second = new OutgoingMessages("uow-2", null, null, List.of("event-2"));

        @Test
        @DisplayName("IN_TRANSACTION keeps a batch only when its transaction commits")
        void inTransactionKeepsOnlyCommitted() {
            CapturingOutbox outbox = CapturingOutbox.inTransaction(boundary);

            LocalTransaction committed = boundary.begin();
            outbox.store(first);
            committed.commit();
            LocalTransaction rolledBack = boundary.begin();
            outbox.store(second);
            rolledBack.rollback();

            assertThat(outbox.timing()).isEqualTo(Outbox.Timing.IN_TRANSACTION);
            assertThat(outbox.received()).containsExactly(first, second);
            assertThat(outbox.committed()).containsExactly(first);
        }

        @Test
        @DisplayName("AFTER_COMMIT keeps every batch it handles; an injected failure is received but not kept")
        void afterCommitWithFailure() {
            CapturingOutbox outbox = CapturingOutbox.afterCommit();
            RuntimeException failure = new IllegalStateException("broker down");
            outbox.failNext(failure);

            assertThatThrownBy(() -> outbox.store(first)).isSameAs(failure);
            outbox.store(second);

            assertThat(outbox.timing()).isEqualTo(Outbox.Timing.AFTER_COMMIT);
            assertThat(outbox.received()).containsExactly(first, second);
            assertThat(outbox.committed()).containsExactly(second);
        }
    }

    @Nested
    @DisplayName("RecordingLogger")
    class Logging {

        private final RecordingLogger logger = new RecordingLogger();

        @Test
        @DisplayName("parses the event name and key=value pairs")
        void parsesEventAndKeys() {
            logger.log(Level.DEBUG, "saga.uow.committed sagaType=X sagaId=Y");

            assertThat(logger.byEvent("saga.uow.committed")).singleElement().satisfies(logged -> {
                assertThat(logged.level()).isEqualTo(Level.DEBUG);
                assertThat(logged.keys()).containsExactly(Map.entry("sagaType", "X"), Map.entry("sagaId", "Y"));
            });
        }

        @Test
        @DisplayName("a value may contain spaces up to the next key; the throwable is kept")
        void valuesWithSpaces() {
            RuntimeException cause = new IllegalStateException("boom");
            logger.log(Level.WARNING, "saga.uow.rolledBack sagaId=s-1 cause=java.lang.IllegalStateException: boom "
                    + "step=pay", cause);

            assertThat(logger.records()).singleElement().satisfies(logged -> {
                assertThat(logged.event()).isEqualTo("saga.uow.rolledBack");
                assertThat(logged.keys()).containsEntry("cause", "java.lang.IllegalStateException: boom")
                        .containsEntry("step", "pay");
                assertThat(logged.thrown()).isSameAs(cause);
            });
            assertThat(logger.byEvent("saga.uow.committed")).isEmpty();
        }
    }
}
