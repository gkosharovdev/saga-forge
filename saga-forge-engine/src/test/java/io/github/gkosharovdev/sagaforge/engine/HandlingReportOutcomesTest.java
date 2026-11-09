package io.github.gkosharovdev.sagaforge.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.core.execution.harness.InMemorySagaContextStore;
import io.github.gkosharovdev.sagaforge.core.execution.harness.MapHandlerInstanceProvider;
import io.github.gkosharovdev.sagaforge.core.execution.harness.RecordingEventDispatcher;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingReport;
import io.github.gkosharovdev.sagaforge.core.spi.SagaHandlingResult;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.core.spi.StepRecord;
import io.github.gkosharovdev.sagaforge.engine.fixtures.concurrency.SignalA;
import io.github.gkosharovdev.sagaforge.engine.fixtures.concurrency.SignalSaga;
import io.github.gkosharovdev.sagaforge.engine.fixtures.concurrency.SignalWorld;
import io.github.gkosharovdev.sagaforge.engine.fixtures.concurrency.TicketClosed;
import io.github.gkosharovdev.sagaforge.engine.fixtures.concurrency.TicketOpened;
import io.github.gkosharovdev.sagaforge.engine.fixtures.failure.FailureFixture;
import io.github.gkosharovdev.sagaforge.engine.fixtures.failure.FaultPoint;
import io.github.gkosharovdev.sagaforge.engine.fixtures.fanout.AuditSaga;
import io.github.gkosharovdev.sagaforge.engine.fixtures.fanout.Case;
import io.github.gkosharovdev.sagaforge.engine.fixtures.fanout.CaseClosed;
import io.github.gkosharovdev.sagaforge.engine.fixtures.fanout.CaseNoted;
import io.github.gkosharovdev.sagaforge.engine.fixtures.fanout.CaseOpened;
import io.github.gkosharovdev.sagaforge.engine.fixtures.fanout.NoteService;
import io.github.gkosharovdev.sagaforge.engine.fixtures.fanout.ReviewSaga;
import io.github.gkosharovdev.sagaforge.engine.support.InMemoryTransactionBoundary;
import io.github.gkosharovdev.sagaforge.engine.support.TransactionalInMemoryStorageProvider;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * One case per row of contracts/event-receiver.md "Outcomes per instance", checking every field's presence rule of
 * data-model.md "SagaHandlingResult" (specs/004-saga-engine tasks.md T092, User Story 5 scenarios 1 and 6). Every
 * outcome, {@code FAILED} included, is returned, not thrown.
 */
class HandlingReportOutcomesTest {

    private static final String TICKET = "ticket-1";

    @AfterEach
    void resetFailure() {
        ReviewSaga.failOnClose(false);
    }

    /** The fields of a result, with the presence of the nullable ones. */
    private record Shape(HandlingOutcome outcome, boolean started, boolean hasSagaId, String outcomeStep,
            boolean hasFailure, boolean hasUnitOfWork, boolean hasHandoffFailures) {

        static Shape of(SagaHandlingResult result) {
            return new Shape(result.outcome(), result.started(), result.sagaId() != null, result.outcomeStep(),
                    result.failure() != null, result.unitOfWorkId() != null, !result.handoffFailures().isEmpty());
        }
    }

    private static SagaHandlingResult onlyResult(HandlingReport report) {
        assertThat(report.results()).hasSize(1);
        return report.results().get(0);
    }

    private static Shape shapeOf(HandlingReport report) {
        return Shape.of(onlyResult(report));
    }

    @Test
    @DisplayName("start, committed at a wait point: WAITING, started, new id, a unit of work")
    void waiting() {
        SagaEngine engine = new SignalWorld().engine();

        assertThat(shapeOf(engine.receive(new TicketOpened(TICKET))))
                .isEqualTo(new Shape(HandlingOutcome.WAITING, true, true, null, false, true, false));
    }

    @Test
    @DisplayName("non-start, committed at @EndSaga: COMPLETED with the outcome step, not started")
    void completed() {
        SagaEngine engine = new SignalWorld().engine();
        engine.receive(new TicketOpened(TICKET));

        assertThat(shapeOf(engine.receive(new TicketClosed(TICKET)))).isEqualTo(
                new Shape(HandlingOutcome.COMPLETED, false, true, SignalSaga.END_STEP, false, true, false));
    }

    @Test
    @DisplayName("start, rolled back: FAILED with a failure, started")
    void failedStarted() {
        FailureFixture fixture = new FailureFixture();
        fixture.plan().arm(FaultPoint.FEE_STEP);

        assertThat(shapeOf(fixture.requestParcel("parcel-1", 4)))
                .isEqualTo(new Shape(HandlingOutcome.FAILED, true, true, null, true, true, false));
    }

    @Test
    @DisplayName("non-start, rolled back: FAILED with a failure, not started")
    void failedNotStarted() {
        InMemoryTransactionBoundary boundary = new InMemoryTransactionBoundary();
        SagaEngine engine = SagaEngine.builder()
                .register(AuditSaga.class, ReviewSaga.class, NoteService.class, Case.class)
                .storage(new TransactionalInMemoryStorageProvider(boundary))
                .contextStore(new InMemorySagaContextStore())
                .transactions(boundary)
                .dispatcher(new RecordingEventDispatcher())
                .handlerInstances(new MapHandlerInstanceProvider().register(new NoteService()))
                .build();
        engine.start();
        engine.receive(new CaseNoted("case-1"));
        ReviewSaga.failOnClose(true);

        HandlingReport report = engine.receive(new CaseClosed("case-1"));

        Map<String, Shape> byType = report.results().stream()
                .collect(Collectors.toMap(SagaHandlingResult::sagaType, Shape::of));
        assertThat(byType.get(ReviewSaga.class.getName()))
                .isEqualTo(new Shape(HandlingOutcome.FAILED, false, true, null, true, true, false));
        assertThat(byType.get(AuditSaga.class.getName()))
                .isEqualTo(new Shape(HandlingOutcome.UNMATCHED, false, false, null, false, false, false));
    }

    @Test
    @DisplayName("start while an active instance has the value: DUPLICATE_START, no id, no unit of work")
    void duplicateStart() {
        SagaEngine engine = new SignalWorld().engine();
        engine.receive(new TicketOpened(TICKET));

        assertThat(shapeOf(engine.receive(new TicketOpened(TICKET))))
                .isEqualTo(new Shape(HandlingOutcome.DUPLICATE_START, false, false, null, false, false, false));
    }

    @Test
    @DisplayName("non-start, no instance correlates: UNMATCHED, no id, no unit of work")
    void unmatched() {
        SagaEngine engine = new SignalWorld().engine();

        assertThat(shapeOf(engine.receive(new SignalA(TICKET))))
                .isEqualTo(new Shape(HandlingOutcome.UNMATCHED, false, false, null, false, false, false));
    }

    @Test
    @DisplayName("non-start, only a terminal instance correlates: UNEXPECTED, that id, no unit of work")
    void unexpected() {
        SagaEngine engine = new SignalWorld().engine();
        engine.receive(new TicketOpened(TICKET));
        engine.receive(new TicketClosed(TICKET));

        assertThat(shapeOf(engine.receive(new SignalA(TICKET))))
                .isEqualTo(new Shape(HandlingOutcome.UNEXPECTED, false, true, null, false, false, false));
    }

    @Test
    @DisplayName("non-start, instance still CREATED: NOT_HANDLED, that id, no unit of work")
    void notHandledCreated() {
        SignalWorld world = new SignalWorld();
        SagaEngine engine = world.engine();
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        world.storage().save(new SagaInstance("seeded", SignalSaga.class.getName(), Map.of(),
                Map.of("ticketId", TICKET), SagaStatus.CREATED, now, now,
                Map.of(SignalSaga.START_STEP, StepRecord.notRun(), SignalSaga.SIGNAL_A_STEP, StepRecord.notRun(),
                        SignalSaga.SIGNAL_B_STEP, StepRecord.notRun(), SignalSaga.END_STEP, StepRecord.notRun()),
                null, null), List.of());

        assertThat(shapeOf(engine.receive(new SignalA(TICKET))))
                .isEqualTo(new Shape(HandlingOutcome.NOT_HANDLED, false, true, null, false, false, false));
    }

    @Test
    @DisplayName("write-time conflict: NOT_HANDLED, that id, with the unit of work's id")
    void notHandledConflict() {
        SignalWorld world = new SignalWorld();
        SagaEngine engine = world.engine();
        String sagaId = onlyResult(engine.receive(new TicketOpened(TICKET))).sagaId();
        world.storage().conflictOnNextSave(sagaId);

        assertThat(shapeOf(engine.receive(new SignalA(TICKET))))
                .isEqualTo(new Shape(HandlingOutcome.NOT_HANDLED, false, true, null, false, true, false));
    }

    @Test
    @DisplayName("no saga reacts: ignored, no result")
    void ignored() {
        SagaEngine engine = new SignalWorld().engine();

        HandlingReport report = engine.receive(new CaseOpened("case-1", null));

        assertThat(report.ignored()).isTrue();
        assertThat(report.results()).isEmpty();
    }
}
