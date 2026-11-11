package io.github.gkosharovdev.sagaforge.engine;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.gkosharovdev.sagaforge.core.execution.harness.InMemorySagaContextStore;
import io.github.gkosharovdev.sagaforge.core.execution.harness.MapHandlerInstanceProvider;
import io.github.gkosharovdev.sagaforge.core.execution.harness.RecordingEventDispatcher;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.Outbox;
import io.github.gkosharovdev.sagaforge.core.spi.OutgoingMessages;
import io.github.gkosharovdev.sagaforge.core.spi.SagaHandlingResult;
import io.github.gkosharovdev.sagaforge.engine.fixtures.fanout.AddNote;
import io.github.gkosharovdev.sagaforge.engine.fixtures.fanout.AuditSaga;
import io.github.gkosharovdev.sagaforge.engine.fixtures.fanout.Case;
import io.github.gkosharovdev.sagaforge.engine.fixtures.fanout.CaseNoted;
import io.github.gkosharovdev.sagaforge.engine.fixtures.fanout.CaseOpened;
import io.github.gkosharovdev.sagaforge.engine.fixtures.fanout.NoteService;
import io.github.gkosharovdev.sagaforge.engine.fixtures.fanout.ReviewSaga;
import io.github.gkosharovdev.sagaforge.engine.support.CapturingOutbox;
import io.github.gkosharovdev.sagaforge.engine.support.InMemoryTransactionBoundary;
import io.github.gkosharovdev.sagaforge.engine.support.TransactionalInMemoryStorageProvider;

/**
 * {@link Outbox#committed} (specs/007-in-memory-transport research §2, tasks.md T018): called once after the commit
 * of a unit of work whose batch an {@code IN_TRANSACTION} outbox stored, with no engine transaction active; never
 * after a rollback, never for {@code AFTER_COMMIT}; a throwing notification changes nothing.
 */
class OutboxCommittedTest {

    private static final String CASE = "case-1";

    private final InMemoryTransactionBoundary boundary = new InMemoryTransactionBoundary();
    private final TransactionalInMemoryStorageProvider storage = new TransactionalInMemoryStorageProvider(boundary);
    private final NoteService notes = new NoteService();

    @BeforeEach
    void setUp() {
        ReviewSaga.failOnClose(false);
        boundary.participant(notes);
    }

    private SagaEngine engine(Outbox outbox) {
        SagaEngine engine = SagaEngine.builder()
                .register(AuditSaga.class, ReviewSaga.class, NoteService.class, Case.class)
                .storage(storage)
                .contextStore(new InMemorySagaContextStore())
                .transactions(boundary)
                .dispatcher(new RecordingEventDispatcher())
                .outbox(outbox)
                .handlerInstances(new MapHandlerInstanceProvider().register(notes))
                .build();
        engine.start();
        return engine;
    }

    @Test
    @DisplayName("an IN_TRANSACTION outbox is notified once, after commit, with the stored batch")
    void notifiedAfterCommit() {
        boolean[] transactionActive = new boolean[1];
        CapturingOutbox capturing = CapturingOutbox.inTransaction(boundary);
        Outbox observing = new Outbox() {
            @Override
            public Timing timing() {
                return Timing.IN_TRANSACTION;
            }

            @Override
            public void store(OutgoingMessages batch) {
                capturing.store(batch);
            }

            @Override
            public void committed(OutgoingMessages batch) {
                transactionActive[0] = boundary.current().isPresent();
                capturing.committed(batch);
            }
        };

        SagaHandlingResult result = engine(observing).receive(new CaseOpened(CASE, AddNote.Mode.NOTE)).results()
                .get(0);

        assertThat(result.outcome()).isEqualTo(HandlingOutcome.WAITING);
        assertThat(capturing.notified()).singleElement().satisfies(batch -> {
            assertThat(batch.unitOfWorkId()).isEqualTo(result.unitOfWorkId());
            assertThat(batch.events()).containsExactly(new CaseNoted(CASE));
        });
        assertThat(capturing.notified()).isEqualTo(capturing.committed());
        assertThat(transactionActive[0]).isFalse();
    }

    @Test
    @DisplayName("a non-saga send notifies after its commit")
    void nonSagaSendNotified() {
        CapturingOutbox capturing = CapturingOutbox.inTransaction(boundary);

        engine(capturing).commandGateway().send(new AddNote(CASE, AddNote.Mode.NOTE));

        assertThat(capturing.notified()).singleElement()
                .satisfies(batch -> assertThat(batch.events()).containsExactly(new CaseNoted(CASE)));
    }

    @Test
    @DisplayName("no notification after a rollback")
    void notNotifiedOnRollback() {
        CapturingOutbox capturing = CapturingOutbox.inTransaction(boundary);
        capturing.failNext(new IllegalStateException("outbox insert failed"));

        SagaHandlingResult result = engine(capturing).receive(new CaseOpened(CASE, AddNote.Mode.NOTE)).results()
                .get(0);

        assertThat(result.outcome()).isEqualTo(HandlingOutcome.FAILED);
        assertThat(capturing.notified()).isEmpty();
    }

    @Test
    @DisplayName("an AFTER_COMMIT outbox is never notified")
    void afterCommitNotNotified() {
        CapturingOutbox capturing = CapturingOutbox.afterCommit();

        engine(capturing).receive(new CaseOpened(CASE, AddNote.Mode.NOTE));

        assertThat(capturing.committed()).hasSize(1);
        assertThat(capturing.notified()).isEmpty();
    }

    @Test
    @DisplayName("a throwing notification changes nothing")
    void throwingNotificationIgnored() {
        CapturingOutbox capturing = CapturingOutbox.inTransaction(boundary);
        capturing.failNextNotification(new IllegalStateException("relay gone"));

        SagaHandlingResult result = engine(capturing).receive(new CaseOpened(CASE, AddNote.Mode.NOTE)).results()
                .get(0);

        assertThat(result.outcome()).isEqualTo(HandlingOutcome.WAITING);
        assertThat(result.outboxFailures()).isEmpty();
        assertThat(capturing.committed()).hasSize(1);
        assertThat(capturing.notified()).hasSize(1);
    }
}
