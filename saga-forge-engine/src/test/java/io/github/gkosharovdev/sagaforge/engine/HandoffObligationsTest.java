package io.github.gkosharovdev.sagaforge.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.core.execution.harness.InMemorySagaContextStore;
import io.github.gkosharovdev.sagaforge.core.execution.harness.MapHandlerInstanceProvider;
import io.github.gkosharovdev.sagaforge.core.execution.harness.RecordingEventDispatcher;
import io.github.gkosharovdev.sagaforge.core.spi.EventDispatcher;
import io.github.gkosharovdev.sagaforge.core.spi.EventHandoff;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingReport;
import io.github.gkosharovdev.sagaforge.core.spi.SagaHandlingResult;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.engine.fixtures.failure.FailureFixture;
import io.github.gkosharovdev.sagaforge.engine.fixtures.failure.FaultPoint;
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
import io.github.gkosharovdev.sagaforge.engine.support.RecordingLogger;
import io.github.gkosharovdev.sagaforge.engine.support.TransactionalInMemoryStorageProvider;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The {@code AFTER_COMMIT} handoff obligations: nothing for a rolled-back unit of work, exactly the buffered events in
 * recording order after commit, each once, and a failing handoff reported without failing the committed saga
 * (specs/004-saga-engine tasks.md T088, User Story 5 scenarios 2 to 4, SC-005, FR-029 to FR-031, quickstart.md
 * scenario 15).
 */
class HandoffObligationsTest {

    private static final String CASE = "case-1";

    private final InMemoryTransactionBoundary boundary = new InMemoryTransactionBoundary();
    private final TransactionalInMemoryStorageProvider storage = new TransactionalInMemoryStorageProvider(boundary);
    private final NoteService notes = new NoteService();
    private final RecordingLogger logger = new RecordingLogger();
    private final List<String> atHandoff = new ArrayList<>();

    @BeforeEach
    void setUp() {
        ReviewSaga.failOnClose(false);
        boundary.participant(notes);
    }

    private SagaEngine auditEngine(EventDispatcher dispatcher, EventHandoff handoff) {
        SagaEngine engine = SagaEngine.builder()
                .register(AuditSaga.class, ReviewSaga.class, NoteService.class, Case.class)
                .storage(storage)
                .contextStore(new InMemorySagaContextStore())
                .transactions(boundary)
                .dispatcher(dispatcher)
                .handoff(handoff)
                .handlerInstances(new MapHandlerInstanceProvider().register(notes))
                .logger(logger)
                .build();
        engine.start();
        return engine;
    }

    /** A capturing handoff whose delegate notes whether a transaction is active and what is committed. */
    private CapturingEventHandoff observingAfterCommit() {
        return CapturingEventHandoff.afterCommit(batch -> {
            atHandoff.add("transaction active: " + boundary.isActive());
            storage.committed().all().forEach(instance -> atHandoff.add("committed: " + instance.status()));
        });
    }

    private static SagaHandlingResult onlyResult(HandlingReport report) {
        assertThat(report.results()).hasSize(1);
        return report.results().get(0);
    }

    @Test
    @DisplayName("US5-2: a rolled-back unit of work hands nothing off")
    void rolledBackHandsOffNothing() {
        FailureFixture fixture = new FailureFixture();
        fixture.plan().arm(FaultPoint.END_STEP);

        SagaHandlingResult result = onlyResult(fixture.requestParcel("parcel-1", 4));

        assertThat(result.outcome()).isEqualTo(HandlingOutcome.FAILED);
        assertThat(fixture.handoff().received()).isEmpty();
        assertThat(fixture.dispatcher().dispatched()).isEmpty();
    }

    @Test
    @DisplayName("US5-3: an event still queued when @EndSaga ended the unit of work is handed off once, after commit")
    void queuedAtEndSagaIsHandedOff() {
        CapturingEventHandoff handoff = observingAfterCommit();
        SagaEngine engine = auditEngine(new RecordingEventDispatcher(), handoff);

        SagaHandlingResult result = onlyResult(engine.receive(new CaseOpened(CASE, AddNote.Mode.CLOSE_THEN_ESCALATE)));

        assertThat(result.outcome()).isEqualTo(HandlingOutcome.COMPLETED);
        assertThat(result.outcomeStep()).isEqualTo(AuditSaga.END_STEP);
        assertThat(handoff.received()).singleElement().satisfies(batch -> {
            assertThat(batch.events()).containsExactly(new CaseEscalated(CASE));
            assertThat(batch.unitOfWorkId()).isEqualTo(result.unitOfWorkId());
            assertThat(batch.sagaType()).isEqualTo(AuditSaga.class.getName());
            assertThat(batch.sagaId()).isEqualTo(result.sagaId());
        });
        assertThat(atHandoff).containsExactly("transaction active: false", "committed: COMPLETED");
    }

    @Test
    @DisplayName("US5-3: an event that triggers another saga type's start step is handed off, never run in-process")
    void startEventOfAnotherSagaIsHandedOff() {
        CapturingEventHandoff handoff = observingAfterCommit();
        SagaEngine engine = auditEngine(new RecordingEventDispatcher(), handoff);

        SagaHandlingResult result = onlyResult(engine.receive(new CaseOpened(CASE, AddNote.Mode.NOTE)));

        assertThat(result.outcome()).isEqualTo(HandlingOutcome.WAITING);
        assertThat(handoff.received()).singleElement()
                .satisfies(batch -> assertThat(batch.events()).containsExactly(new CaseNoted(CASE)));
        assertThat(storage.committed().all()).singleElement()
                .satisfies(instance -> assertThat(instance.sagaType()).isEqualTo(AuditSaga.class.getName()));
        assertThat(atHandoff).containsExactly("transaction active: false", "committed: RUNNING");
    }

    @Test
    @DisplayName("US5-4: a handoff failing after commit leaves the saga committed and lists the failure")
    void failingHandoffAfterCommit() {
        CapturingEventHandoff handoff = CapturingEventHandoff.afterCommit();
        IllegalStateException failure = new IllegalStateException("broker unavailable");
        failure.addSuppressed(new IllegalArgumentException("second event rejected"));
        handoff.failNext(failure);
        SagaEngine engine = auditEngine(new RecordingEventDispatcher(), handoff);

        SagaHandlingResult result = onlyResult(engine.receive(new CaseOpened(CASE, AddNote.Mode.NOTE)));

        assertThat(result.outcome()).isEqualTo(HandlingOutcome.WAITING);
        assertThat(result.failure()).isNull();
        assertThat(result.handoffFailures()).containsExactly(failure, failure.getSuppressed()[0]);
        assertThat(storage.committed().findById(result.sagaId())).hasValueSatisfying(
                instance -> assertThat(instance.status()).isEqualTo(SagaStatus.RUNNING));
        assertThat(logger.byEvent("saga.handoff.failed")).singleElement().satisfies(logged -> {
            assertThat(logged.keys()).containsEntry("sagaId", result.sagaId())
                    .containsEntry("uow", result.unitOfWorkId()).containsEntry("events", "1");
            assertThat(logged.thrown()).isSameAs(failure);
        });
    }

    @Test
    @DisplayName("DispatchingEventHandoff: a failing dispatch is listed as a handoff failure of the committed saga")
    void defaultHandoffListsDispatchFailure() {
        IllegalStateException failure = new IllegalStateException("dispatch failed");
        FailingDispatcher dispatcher = new FailingDispatcher(CaseNoted.class, failure);
        SagaEngine engine = auditEngine(dispatcher, new DispatchingEventHandoff(dispatcher));

        SagaHandlingResult result = onlyResult(engine.receive(new CaseOpened(CASE, AddNote.Mode.NOTE)));

        assertThat(result.outcome()).isEqualTo(HandlingOutcome.WAITING);
        assertThat(result.handoffFailures()).containsExactly(failure);
    }

    @Test
    @DisplayName("DispatchingEventHandoff outside a saga: the event after a failing one is still dispatched")
    void defaultHandoffDispatchesTheRest() {
        IllegalStateException failure = new IllegalStateException("dispatch failed");
        FailingDispatcher dispatcher = new FailingDispatcher(CaseClosed.class, failure);
        SagaEngine engine = auditEngine(dispatcher, new DispatchingEventHandoff(dispatcher));

        engine.commandGateway().send(new AddNote(CASE, AddNote.Mode.CLOSE_THEN_ESCALATE));

        assertThat(dispatcher.attempted).containsExactly(new CaseClosed(CASE), new CaseEscalated(CASE));
        assertThat(notes.notedCases()).containsExactly(CASE);
        assertThat(logger.byEvent("saga.handoff.failed")).singleElement().satisfies(logged -> {
            assertThat(logged.keys()).doesNotContainKeys("sagaType", "sagaId").containsEntry("events", "2");
            assertThat(logged.thrown()).isSameAs(failure);
        });
    }

    /** Records every dispatch and fails for events of one class. */
    private static final class FailingDispatcher implements EventDispatcher {

        private final Class<?> failingType;
        private final RuntimeException failure;
        private final List<Object> attempted = new ArrayList<>();

        private FailingDispatcher(Class<?> failingType, RuntimeException failure) {
            this.failingType = failingType;
            this.failure = failure;
        }

        @Override
        public void dispatch(Object domainEvent) {
            attempted.add(domainEvent);
            if (failingType.isInstance(domainEvent)) {
                throw failure;
            }
        }

        @Override
        public void registerSagaType(Class<?> sagaClass) {
        }
    }
}
