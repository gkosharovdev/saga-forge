package io.github.gkosharovdev.sagaforge.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gkosharovdev.sagaforge.core.execution.harness.InMemorySagaContextStore;
import io.github.gkosharovdev.sagaforge.core.execution.harness.MapHandlerInstanceProvider;
import io.github.gkosharovdev.sagaforge.core.execution.harness.RecordingEventDispatcher;
import io.github.gkosharovdev.sagaforge.core.spi.Outbox;
import io.github.gkosharovdev.sagaforge.core.spi.OutgoingMessages;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingReport;
import io.github.gkosharovdev.sagaforge.core.spi.LocalTransaction;
import io.github.gkosharovdev.sagaforge.core.spi.SagaHandlingResult;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.fanout.AddNote;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.fanout.AuditSaga;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.fanout.Case;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.fanout.CaseClosed;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.fanout.CaseEscalated;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.fanout.CaseNoted;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.fanout.CaseOpened;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.fanout.NoteService;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.fanout.ReviewSaga;
import io.github.gkosharovdev.sagaforge.runtime.support.CapturingOutbox;
import io.github.gkosharovdev.sagaforge.runtime.support.InMemoryTransactionBoundary;
import io.github.gkosharovdev.sagaforge.runtime.support.TransactionalInMemoryStorageProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The {@code IN_TRANSACTION} outbox: each committed unit of work's batch arrives before commit, inside its
 * transaction, and is kept only if that transaction commits; a failing outbox fails the unit of work
 * (specs/004-saga-engine tasks.md T089, User Story 5 scenario 7, SC-011, quickstart.md scenario 16). The engine is
 * built as in the default case except for {@code .outbox(...)}.
 */
class InTransactionOutboxTest {

    private static final String CASE = "case-1";

    private final InMemoryTransactionBoundary boundary = new InMemoryTransactionBoundary();
    private final TransactionalInMemoryStorageProvider storage = new TransactionalInMemoryStorageProvider(boundary);
    private final NoteService notes = new NoteService();
    private final CapturingOutbox capturing = CapturingOutbox.inTransaction(boundary);

    /** What the outbox saw when it was called. */
    private record Seen(Optional<LocalTransaction> transaction, List<SagaStatus> committedStatuses) {
    }

    private final List<Seen> seen = new ArrayList<>();

    @BeforeEach
    void setUp() {
        ReviewSaga.failOnClose(false);
        boundary.participant(notes);
    }

    private SagaRuntime engine(Outbox outbox) {
        SagaRuntime engine = SagaRuntime.builder()
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

    /** An {@code IN_TRANSACTION} outbox that notes what it sees, runs {@code before}, then delegates to capturing. */
    private Outbox observing(Runnable before) {
        return new Outbox() {
            @Override
            public Timing timing() {
                return Timing.IN_TRANSACTION;
            }

            @Override
            public void store(OutgoingMessages batch) {
                seen.add(new Seen(boundary.current(),
                        storage.committed().all().stream().map(instance -> instance.status()).toList()));
                before.run();
                capturing.store(batch);
            }
        };
    }

    private static SagaHandlingResult onlyResult(HandlingReport report) {
        assertThat(report.results()).hasSize(1);
        return report.results().get(0);
    }

    @Test
    @DisplayName("the batch arrives before commit, inside the unit of work's transaction, and is kept after commit")
    void batchInsideTransactionKeptOnCommit() {
        SagaRuntime engine = engine(observing(() -> { }));

        SagaHandlingResult result = onlyResult(engine.receive(new CaseOpened(CASE, AddNote.Mode.NOTE)));

        assertThat(result.outcome()).isEqualTo(HandlingOutcome.WAITING);
        assertThat(result.outboxFailures()).isEmpty();
        assertThat(seen).singleElement().satisfies(atOutbox -> {
            assertThat(atOutbox.transaction()).isPresent();
            // The instance is still as its creation write left it: the unit of work has not committed yet.
            assertThat(atOutbox.committedStatuses()).containsExactly(SagaStatus.CREATED);
        });
        assertThat(capturing.committed()).singleElement().satisfies(batch -> {
            assertThat(batch.unitOfWorkId()).isEqualTo(result.unitOfWorkId());
            assertThat(batch.sagaType()).isEqualTo(AuditSaga.class.getName());
            assertThat(batch.sagaId()).isEqualTo(result.sagaId());
            assertThat(batch.events()).containsExactly(new CaseNoted(CASE));
        });
    }

    @Test
    @DisplayName("events still queued at @EndSaga arrive in recording order")
    void queuedEventsInRecordingOrder() {
        SagaRuntime engine = engine(capturing);

        SagaHandlingResult result = onlyResult(engine.receive(new CaseOpened(CASE, AddNote.Mode.CLOSE_THEN_ESCALATE)));

        assertThat(result.outcome()).isEqualTo(HandlingOutcome.COMPLETED);
        assertThat(capturing.committed()).singleElement()
                .satisfies(batch -> assertThat(batch.events()).containsExactly(new CaseEscalated(CASE)));
    }

    @Test
    @DisplayName("a commit failing after the outbox keeps nothing; the saga ends FAILED")
    void failedCommitKeepsNothing() {
        IllegalStateException commitFailure = new IllegalStateException("commit failed");
        SagaRuntime engine = engine(observing(() -> boundary.failNextCommit(commitFailure)));

        SagaHandlingResult result = onlyResult(engine.receive(new CaseOpened(CASE, AddNote.Mode.NOTE)));

        assertThat(result.outcome()).isEqualTo(HandlingOutcome.FAILED);
        assertThat(capturing.received()).hasSize(1);
        assertThat(capturing.committed()).isEmpty();
        assertThat(notes.notedCases()).isEmpty();
    }

    @Test
    @DisplayName("a throwing outbox fails the unit of work, naming the last step run")
    void throwingOutboxFailsTheSaga() {
        IllegalStateException failure = new IllegalStateException("outbox insert failed");
        capturing.failNext(failure);
        SagaRuntime engine = engine(capturing);

        SagaHandlingResult result = onlyResult(engine.receive(new CaseOpened(CASE, AddNote.Mode.NOTE)));

        assertThat(result.outcome()).isEqualTo(HandlingOutcome.FAILED);
        assertThat(result.failure().stepName()).isEqualTo(AuditSaga.START_STEP);
        assertThat(result.failure().causeType()).isEqualTo(IllegalStateException.class.getName());
        assertThat(storage.committed().findById(result.sagaId())).hasValueSatisfying(
                instance -> assertThat(instance.status()).isEqualTo(SagaStatus.FAILED));
        assertThat(capturing.committed()).isEmpty();
        assertThat(notes.notedCases()).isEmpty();
    }

    @Test
    @DisplayName("a non-saga send hands its events off inside its transaction, without saga type or id")
    void nonSagaSendInsideTransaction() {
        SagaRuntime engine = engine(capturing);

        engine.commandGateway().send(new AddNote(CASE, AddNote.Mode.CLOSE_THEN_ESCALATE));

        assertThat(capturing.committed()).singleElement().satisfies(batch -> {
            assertThat(batch.sagaType()).isNull();
            assertThat(batch.sagaId()).isNull();
            assertThat(batch.events()).containsExactly(new CaseClosed(CASE), new CaseEscalated(CASE));
        });
    }

    @Test
    @DisplayName("a non-saga send whose outbox throws rolls back and rethrows")
    void nonSagaSendOutboxFailureRollsBack() {
        IllegalStateException failure = new IllegalStateException("outbox insert failed");
        capturing.failNext(failure);
        SagaRuntime engine = engine(capturing);

        assertThatThrownBy(() -> engine.commandGateway().send(new AddNote(CASE, AddNote.Mode.NOTE))).isSameAs(failure);

        assertThat(notes.notedCases()).isEmpty();
        assertThat(capturing.committed()).isEmpty();
        assertThat(boundary.commits()).isZero();
    }
}
