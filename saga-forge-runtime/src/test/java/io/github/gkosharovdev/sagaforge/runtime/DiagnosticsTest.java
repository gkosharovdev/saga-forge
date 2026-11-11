package io.github.gkosharovdev.sagaforge.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.gkosharovdev.sagaforge.core.execution.harness.InMemorySagaContextStore;
import io.github.gkosharovdev.sagaforge.core.execution.harness.RecordingEventDispatcher;
import io.github.gkosharovdev.sagaforge.core.spi.EventHandlingException;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingReport;
import io.github.gkosharovdev.sagaforge.core.spi.SagaHandlingResult;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.core.spi.StepRecord;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.concurrency.SignalA;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.concurrency.SignalSaga;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.concurrency.SignalWorld;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.concurrency.TicketClosed;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.concurrency.TicketOpened;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.failure.FailureFixture;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.failure.FailureSaga;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.failure.FaultPoint;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.startup.ThingStarted;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.startup.UnloadableAggregate;
import io.github.gkosharovdev.sagaforge.runtime.support.InMemoryTransactionBoundary;
import io.github.gkosharovdev.sagaforge.runtime.support.RecordingLogger;
import io.github.gkosharovdev.sagaforge.runtime.support.RecordingLogger.Logged;
import io.github.gkosharovdev.sagaforge.runtime.support.TransactionalInMemoryStorageProvider;
import java.lang.System.Logger.Level;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every diagnostic of contracts/diagnostics.md is written at its level, with the keys the table lists and the
 * throwable attached where it says so (specs/004-saga-engine tasks.md T102, FR-038, quickstart.md scenario 22).
 */
class DiagnosticsTest {

    private static final String TICKET = "ticket-1";

    /** The only message named {@code event}, checked for its level and keys. */
    private static Logged logged(RecordingLogger logger, String event, Level level, String... keys) {
        List<Logged> named = logger.byEvent(event);
        assertThat(named).as("messages named %s", event).isNotEmpty();
        Logged first = named.get(0);
        assertThat(first.level()).as("level of %s", event).isEqualTo(level);
        assertThat(first.keys()).as("keys of %s", event).containsKeys(keys);
        return first;
    }

    private static SagaHandlingResult onlyResult(HandlingReport report) {
        assertThat(report.results()).hasSize(1);
        return report.results().get(0);
    }

    @Test
    @DisplayName("startup: saga.startup.refused (count, then one line per error) and saga.startup.started")
    void startup() {
        RecordingLogger refusedLog = new RecordingLogger();
        InMemoryTransactionBoundary boundary = new InMemoryTransactionBoundary();
        SagaRuntime refused = SagaRuntime.builder()
                .register(SignalSaga.class, UnloadableAggregate.class)
                .storage(new TransactionalInMemoryStorageProvider(boundary))
                .contextStore(new InMemorySagaContextStore())
                .transactions(boundary)
                .dispatcher(new RecordingEventDispatcher())
                .logger(refusedLog)
                .build();
        catchThrowableOfType(SagaRuntimeStartupException.class, refused::start);

        assertThat(refusedLog.byEvent("saga.startup.refused")).hasSize(3).allSatisfy(logged ->
                assertThat(logged.level()).isEqualTo(Level.ERROR));
        assertThat(refusedLog.byEvent("saga.startup.refused").get(0).keys()).containsEntry("errors", "2");
        assertThat(refusedLog.byEvent("saga.startup.refused").subList(1, 3))
                .allSatisfy(logged -> assertThat(logged.keys()).containsKey("error"));

        SignalWorld world = new SignalWorld();
        world.engine();
        assertThat(logged(world.logger(), "saga.startup.started", Level.INFO, "sagaTypes").keys())
                .containsEntry("sagaTypes", SignalSaga.class.getName());
    }

    @Test
    @DisplayName("routing: ignored, unmatched, unexpected, duplicate start, committed, not started, conflict")
    void routing() {
        SignalWorld world = new SignalWorld();
        SagaRuntime engine = world.engine();
        RecordingLogger logger = world.logger();

        engine.receive(new ThingStarted("thing-1"));
        logged(logger, "saga.event.ignored", Level.DEBUG, "event");

        engine.receive(new SignalA(TICKET));
        logged(logger, "saga.event.unmatched", Level.DEBUG, "event", "sagaType", "step");

        String sagaId = onlyResult(engine.receive(new TicketOpened(TICKET))).sagaId();
        Logged committed = logged(logger, "saga.uow.committed", Level.DEBUG, "event", "sagaType", "sagaId", "uow",
                "outcome", "steps");
        assertThat(committed.keys()).containsEntry("sagaId", sagaId).containsEntry("outcome", "WAITING");

        engine.receive(new TicketOpened(TICKET));
        logged(logger, "saga.event.duplicateStart", Level.INFO, "event", "sagaType", "associationProperty",
                "associationValue");

        world.storage().conflictOnNextSave(sagaId);
        engine.receive(new SignalA(TICKET));
        assertThat(logged(logger, "saga.uow.conflict", Level.WARNING, "event", "sagaType", "sagaId", "uow").keys())
                .containsEntry("sagaId", sagaId);

        engine.receive(new TicketClosed(TICKET));
        engine.receive(new SignalA(TICKET));
        assertThat(logged(logger, "saga.event.unexpected", Level.INFO, "event", "sagaType", "sagaId", "step")
                .keys()).containsEntry("sagaId", sagaId);

        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        world.storage().save(new SagaInstance("seeded", SignalSaga.class.getName(), Map.of(),
                Map.of("ticketId", "ticket-2"), SagaStatus.CREATED, now, now,
                Map.of(SignalSaga.START_STEP, StepRecord.notRun(), SignalSaga.SIGNAL_A_STEP, StepRecord.notRun(),
                        SignalSaga.SIGNAL_B_STEP, StepRecord.notRun(), SignalSaga.END_STEP, StepRecord.notRun()),
                null, null), List.of());
        engine.receive(new SignalA("ticket-2"));
        assertThat(logged(logger, "saga.uow.notStarted", Level.INFO, "event", "sagaType", "sagaId").keys())
                .containsEntry("sagaId", "seeded");
    }

    @Test
    @DisplayName("failure: saga.uow.rolledBack and saga.instance.failed, with the throwable on the rollback")
    void failure() {
        FailureFixture fixture = new FailureFixture();
        fixture.plan().arm(FaultPoint.START_STEP);

        String sagaId = onlyResult(fixture.requestParcel("parcel-1", 4)).sagaId();

        Logged rolledBack = logged(fixture.logger(), "saga.uow.rolledBack", Level.WARNING, "event", "sagaType",
                "sagaId", "uow", "step", "cause");
        assertThat(rolledBack.keys()).containsEntry("sagaId", sagaId).containsEntry("step", FailureSaga.START_STEP);
        assertThat(rolledBack.thrown()).isNotNull();
        assertThat(logged(fixture.logger(), "saga.instance.failed", Level.WARNING, "sagaType", "sagaId", "step",
                "cause").keys()).containsEntry("sagaId", sagaId);
    }

    @Test
    @DisplayName("no outcome: saga.instance.failedNotRecorded and saga.event.notHandled, each with the throwable")
    void notHandled() {
        FailureFixture fixture = new FailureFixture();
        IllegalStateException lockFailure = new IllegalStateException("store unavailable");
        fixture.plan().arm(FaultPoint.START_STEP).duringStep(FailureSaga.START_STEP,
                () -> fixture.storage().failNextFindByIdAndLock(lockFailure));

        EventHandlingException thrown = catchThrowableOfType(EventHandlingException.class,
                () -> fixture.requestParcel("parcel-1", 4));

        assertThat(logged(fixture.logger(), "saga.instance.failedNotRecorded", Level.ERROR, "sagaType", "sagaId",
                "cause").thrown()).isSameAs(lockFailure);
        assertThat(logged(fixture.logger(), "saga.event.notHandled", Level.ERROR, "event").thrown()).isSameAs(thrown);
    }

    @Test
    @DisplayName("outbox: saga.outbox.failed with the unit of work, saga, event count and throwable")
    void outboxFailed() {
        FailureFixture fixture = new FailureFixture();
        IllegalStateException failure = new IllegalStateException("broker unavailable");
        fixture.outbox().failNext(failure);

        SagaHandlingResult result = onlyResult(fixture.requestParcel("parcel-1", 4));

        Logged outbox = logged(fixture.logger(), "saga.outbox.failed", Level.ERROR, "uow", "sagaType", "sagaId",
                "events");
        assertThat(outbox.keys()).containsEntry("uow", result.unitOfWorkId()).containsEntry("events", "1");
        assertThat(outbox.thrown()).isSameAs(failure);
    }
}
