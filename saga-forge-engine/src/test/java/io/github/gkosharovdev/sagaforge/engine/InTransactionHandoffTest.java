package io.github.gkosharovdev.sagaforge.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gkosharovdev.sagaforge.core.execution.harness.InMemorySagaContextStore;
import io.github.gkosharovdev.sagaforge.core.execution.harness.MapHandlerInstanceProvider;
import io.github.gkosharovdev.sagaforge.core.execution.harness.RecordingEventDispatcher;
import io.github.gkosharovdev.sagaforge.core.spi.EventHandoff;
import io.github.gkosharovdev.sagaforge.core.spi.HandedOffEvents;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingReport;
import io.github.gkosharovdev.sagaforge.core.spi.LocalTransaction;
import io.github.gkosharovdev.sagaforge.core.spi.SagaHandlingResult;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.engine.fixtures.fanout.AddNote;
import io.github.gkosharovdev.sagaforge.engine.fixtures.fanout.AuditSaga;
import io.github.gkosharovdev.sagaforge.engine.fixtures.fanout.Case;
import io.github.gkosharovdev.sagaforge.engine.fixtures.fanout.CaseClosed;
import io.github.gkosharovdev.sagaforge.engine.fixtures.fanout.CaseEscalated;
import io.github.gkosharovdev.sagaforge.engine.fixtures.fanout.CaseNoted;
import io.github.gkosharovdev.sagaforge.engine.fixtures.fanout.CaseOpened;
import io.github.gkosharovdev.sagaforge.engine.fixtures.fanout.NoteService;
import io.github.gkosharovdev.sagaforge.engine.fixtures.fanout.ReviewSaga;
import io.github.gkosharovdev.sagaforge.engine.support.CapturingEventHandoff;
import io.github.gkosharovdev.sagaforge.engine.support.InMemoryTransactionBoundary;
import io.github.gkosharovdev.sagaforge.engine.support.TransactionalInMemoryStorageProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The {@code IN_TRANSACTION} handoff: each committed unit of work's batch arrives before commit, inside its
 * transaction, and is kept only if that transaction commits; a failing handoff fails the unit of work
 * (specs/004-saga-engine tasks.md T089, User Story 5 scenario 7, SC-011, quickstart.md scenario 16). The engine is
 * built as in the default case except for {@code .handoff(...)}.
 */
class InTransactionHandoffTest {

    private static final String CASE = "case-1";

    private final InMemoryTransactionBoundary boundary = new InMemoryTransactionBoundary();
    private final TransactionalInMemoryStorageProvider storage = new TransactionalInMemoryStorageProvider(boundary);
    private final NoteService notes = new NoteService();
    private final CapturingEventHandoff capturing = CapturingEventHandoff.inTransaction(boundary);

    /** What the handoff saw when it was called. */
    private record Seen(Optional<LocalTransaction> transaction, List<SagaStatus> committedStatuses) {
    }

    private final List<Seen> seen = new ArrayList<>();

    @BeforeEach
    void setUp() {
        ReviewSaga.failOnClose(false);
        boundary.participant(notes);
    }

    private SagaEngine engine(EventHandoff handoff) {
        SagaEngine engine = SagaEngine.builder()
                .register(AuditSaga.class, ReviewSaga.class, NoteService.class, Case.class)
                .storage(storage)
                .contextStore(new InMemorySagaContextStore())
                .transactions(boundary)
                .dispatcher(new RecordingEventDispatcher())
                .handoff(handoff)
                .handlerInstances(new MapHandlerInstanceProvider().register(notes))
                .build();
        engine.start();
        return engine;
    }

    /** An {@code IN_TRANSACTION} handoff that notes what it sees, runs {@code before}, then delegates to capturing. */
    private EventHandoff observing(Runnable before) {
        return new EventHandoff() {
            @Override
            public Timing timing() {
                return Timing.IN_TRANSACTION;
            }

            @Override
            public void handOff(HandedOffEvents batch) {
                seen.add(new Seen(boundary.current(),
                        storage.committed().all().stream().map(instance -> instance.status()).toList()));
                before.run();
                capturing.handOff(batch);
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
        SagaEngine engine = engine(observing(() -> { }));

        SagaHandlingResult result = onlyResult(engine.receive(new CaseOpened(CASE, AddNote.Mode.NOTE)));

        assertThat(result.outcome()).isEqualTo(HandlingOutcome.WAITING);
        assertThat(result.handoffFailures()).isEmpty();
        assertThat(seen).singleElement().satisfies(atHandoff -> {
            assertThat(atHandoff.transaction()).isPresent();
            // The instance is still as its creation write left it: the unit of work has not committed yet.
            assertThat(atHandoff.committedStatuses()).containsExactly(SagaStatus.CREATED);
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
        SagaEngine engine = engine(capturing);

        SagaHandlingResult result = onlyResult(engine.receive(new CaseOpened(CASE, AddNote.Mode.CLOSE_THEN_ESCALATE)));

        assertThat(result.outcome()).isEqualTo(HandlingOutcome.COMPLETED);
        assertThat(capturing.committed()).singleElement()
                .satisfies(batch -> assertThat(batch.events()).containsExactly(new CaseEscalated(CASE)));
    }

    @Test
    @DisplayName("a commit failing after the handoff keeps nothing; the saga ends FAILED")
    void failedCommitKeepsNothing() {
        IllegalStateException commitFailure = new IllegalStateException("commit failed");
        SagaEngine engine = engine(observing(() -> boundary.failNextCommit(commitFailure)));

        SagaHandlingResult result = onlyResult(engine.receive(new CaseOpened(CASE, AddNote.Mode.NOTE)));

        assertThat(result.outcome()).isEqualTo(HandlingOutcome.FAILED);
        assertThat(capturing.received()).hasSize(1);
        assertThat(capturing.committed()).isEmpty();
        assertThat(notes.notedCases()).isEmpty();
    }

    @Test
    @DisplayName("a throwing handoff fails the unit of work, naming the last step run")
    void throwingHandoffFailsTheSaga() {
        IllegalStateException failure = new IllegalStateException("outbox insert failed");
        capturing.failNext(failure);
        SagaEngine engine = engine(capturing);

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
        SagaEngine engine = engine(capturing);

        engine.commandGateway().send(new AddNote(CASE, AddNote.Mode.CLOSE_THEN_ESCALATE));

        assertThat(capturing.committed()).singleElement().satisfies(batch -> {
            assertThat(batch.sagaType()).isNull();
            assertThat(batch.sagaId()).isNull();
            assertThat(batch.events()).containsExactly(new CaseClosed(CASE), new CaseEscalated(CASE));
        });
    }

    @Test
    @DisplayName("a non-saga send whose handoff throws rolls back and rethrows")
    void nonSagaSendHandoffFailureRollsBack() {
        IllegalStateException failure = new IllegalStateException("outbox insert failed");
        capturing.failNext(failure);
        SagaEngine engine = engine(capturing);

        assertThatThrownBy(() -> engine.commandGateway().send(new AddNote(CASE, AddNote.Mode.NOTE))).isSameAs(failure);

        assertThat(notes.notedCases()).isEmpty();
        assertThat(capturing.committed()).isEmpty();
        assertThat(boundary.commits()).isZero();
    }
}
