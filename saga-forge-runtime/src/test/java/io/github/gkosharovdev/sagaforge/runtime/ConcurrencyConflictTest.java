package io.github.gkosharovdev.sagaforge.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.core.spi.HandlingOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingReport;
import io.github.gkosharovdev.sagaforge.core.spi.SagaContextRecord;
import io.github.gkosharovdev.sagaforge.core.spi.SagaHandlingResult;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaLifecycleEntry;
import io.github.gkosharovdev.sagaforge.core.spi.StepStatus;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.concurrency.SignalA;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.concurrency.SignalSaga;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.concurrency.SignalWorld;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.concurrency.TicketOpened;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A write-time {@code SagaConcurrencyConflictException} is reported {@code NOT_HANDLED}, never {@code FAILED}, and the
 * event can be redelivered (specs/004-saga-engine tasks.md T078, User Story 4 scenario 4, FR-026, quickstart.md
 * scenario 11).
 */
class ConcurrencyConflictTest {

    private static final String TICKET = "ticket-1";

    private static SagaHandlingResult onlyResult(HandlingReport report) {
        assertThat(report.results()).hasSize(1);
        return report.results().get(0);
    }

    @Test
    @DisplayName("a write-time conflict rolls back, writes no FAILED, reports NOT_HANDLED; redelivery works")
    void conflictIsNotHandledAndRedeliverable() {
        SignalWorld world = new SignalWorld();
        SagaRuntime engine = world.engine();
        String sagaId = onlyResult(engine.receive(new TicketOpened(TICKET))).sagaId();
        SagaInstance before = world.committed(sagaId);
        List<SagaLifecycleEntry> historyBefore = world.history(sagaId);
        int savesBefore = world.storage().saveCalls();
        world.storage().conflictOnNextSave(sagaId);

        SagaHandlingResult conflicted = onlyResult(engine.receive(new SignalA(TICKET)));

        assertThat(conflicted.outcome()).isEqualTo(HandlingOutcome.NOT_HANDLED);
        assertThat(conflicted.sagaId()).isEqualTo(sagaId);
        assertThat(conflicted.unitOfWorkId()).isNotNull();
        assertThat(conflicted.failure()).isNull();
        assertThat(world.storage().saveCalls()).isEqualTo(savesBefore);
        assertThat(world.committed(sagaId)).isEqualTo(before);
        assertThat(world.history(sagaId)).isEqualTo(historyBefore);
        assertThat(world.contextStore().find(sagaId)).map(SagaContextRecord::values)
                .hasValueSatisfying(values -> assertThat(values).containsKey(SignalSaga.CONTEXT_A));
        assertThat(world.logger().byEvent("saga.uow.conflict")).singleElement().satisfies(logged ->
                assertThat(logged.keys()).containsEntry("sagaId", sagaId)
                        .containsEntry("uow", conflicted.unitOfWorkId()));
        assertThat(world.logger().byEvent("saga.uow.rolledBack")).isEmpty();
        assertThat(world.logger().byEvent("saga.instance.failed")).isEmpty();

        SagaHandlingResult redelivered = onlyResult(engine.receive(new SignalA(TICKET)));

        assertThat(redelivered.outcome()).isEqualTo(HandlingOutcome.WAITING);
        assertThat(world.committed(sagaId).steps().get(SignalSaga.SIGNAL_A_STEP).status())
                .isEqualTo(StepStatus.COMPLETED);
    }
}
