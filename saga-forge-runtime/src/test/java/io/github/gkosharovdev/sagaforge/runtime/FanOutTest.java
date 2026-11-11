package io.github.gkosharovdev.sagaforge.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.core.execution.harness.InMemorySagaContextStore;
import io.github.gkosharovdev.sagaforge.core.execution.harness.MapHandlerInstanceProvider;
import io.github.gkosharovdev.sagaforge.core.execution.harness.RecordingEventDispatcher;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingReport;
import io.github.gkosharovdev.sagaforge.core.spi.SagaHandlingResult;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.fanout.AddNote;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.fanout.AuditSaga;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.fanout.Case;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.fanout.CaseClosed;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.fanout.CaseNoted;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.fanout.CaseOpened;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.fanout.NoteService;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.fanout.ReviewSaga;
import io.github.gkosharovdev.sagaforge.runtime.support.InMemoryTransactionBoundary;
import io.github.gkosharovdev.sagaforge.runtime.support.TransactionalInMemoryStorageProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * One event reacted to by several saga types runs one unit of work per saga type, each committed or failed on its
 * own, reported in registration order (specs/004-saga-engine tasks.md T069, spec Edge Cases "Event reacted to by
 * several saga types").
 */
class FanOutTest {

    private static final String CASE = "case-1";

    private final InMemoryTransactionBoundary boundary = new InMemoryTransactionBoundary();
    private final TransactionalInMemoryStorageProvider storage = new TransactionalInMemoryStorageProvider(boundary);
    private final RecordingEventDispatcher dispatcher = new RecordingEventDispatcher();
    private final NoteService notes = new NoteService();
    private SagaRuntime engine;

    @BeforeEach
    void setUp() {
        ReviewSaga.failOnClose(false);
        boundary.participant(notes);
        engine = SagaRuntime.builder()
                .register(AuditSaga.class, ReviewSaga.class, NoteService.class, Case.class)
                .storage(storage)
                .contextStore(new InMemorySagaContextStore())
                .transactions(boundary)
                .dispatcher(dispatcher)
                .handlerInstances(new MapHandlerInstanceProvider().register(notes))
                .build();
        engine.start();
    }

    @AfterEach
    void resetFailure() {
        ReviewSaga.failOnClose(false);
    }

    /** Opens the case (an AuditSaga waits) and delivers the dispatched CaseNoted (a ReviewSaga waits). */
    private void openAndReview() {
        SagaHandlingResult audit = onlyResult(engine.receive(new CaseOpened(CASE, AddNote.Mode.NOTE)));
        assertThat(audit.outcome()).isEqualTo(HandlingOutcome.WAITING);
        assertThat(dispatcher.dispatched()).containsExactly(new CaseNoted(CASE));

        SagaHandlingResult review = onlyResult(engine.receive(dispatcher.dispatched().get(0)));
        assertThat(review.outcome()).isEqualTo(HandlingOutcome.WAITING);
        assertThat(review.sagaType()).isEqualTo(ReviewSaga.class.getName());
    }

    private static SagaHandlingResult onlyResult(HandlingReport report) {
        assertThat(report.results()).hasSize(1);
        return report.results().get(0);
    }

    private SagaInstance committedOf(SagaHandlingResult result) {
        return storage.committed().findById(result.sagaId()).orElseThrow();
    }

    @Test
    @DisplayName("CaseClosed runs one unit of work per saga type, in registration order, each COMPLETED")
    void oneUnitOfWorkPerSagaType() {
        openAndReview();
        int commitsBefore = boundary.commits();

        HandlingReport report = engine.receive(new CaseClosed(CASE));

        assertThat(report.results()).extracting(SagaHandlingResult::sagaType)
                .containsExactly(AuditSaga.class.getName(), ReviewSaga.class.getName());
        assertThat(report.results()).allSatisfy(result -> {
            assertThat(result.outcome()).isEqualTo(HandlingOutcome.COMPLETED);
            assertThat(result.started()).isFalse();
            assertThat(committedOf(result).status()).isEqualTo(SagaStatus.COMPLETED);
        });
        assertThat(report.results().get(0).unitOfWorkId()).isNotEqualTo(report.results().get(1).unitOfWorkId());
        assertThat(boundary.commits() - commitsBefore).isEqualTo(2);
    }

    @Test
    @DisplayName("a failing ReviewSaga ends FAILED while AuditSaga commits COMPLETED")
    void failureIsIsolatedPerSagaType() {
        openAndReview();
        ReviewSaga.failOnClose(true);

        HandlingReport report = engine.receive(new CaseClosed(CASE));

        SagaHandlingResult audit = report.results().get(0);
        SagaHandlingResult review = report.results().get(1);
        assertThat(audit.sagaType()).isEqualTo(AuditSaga.class.getName());
        assertThat(audit.outcome()).isEqualTo(HandlingOutcome.COMPLETED);
        assertThat(committedOf(audit).status()).isEqualTo(SagaStatus.COMPLETED);
        assertThat(review.sagaType()).isEqualTo(ReviewSaga.class.getName());
        assertThat(review.outcome()).isEqualTo(HandlingOutcome.FAILED);
        assertThat(review.failure().stepName()).isEqualTo(ReviewSaga.END_STEP);
        assertThat(committedOf(review).status()).isEqualTo(SagaStatus.FAILED);
        assertThat(notes.notedCases()).containsExactly(CASE);
    }
}
