package io.github.gkosharovdev.sagaforge.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.core.spi.CompensationStatus;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.SagaHandlingResult;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.core.spi.StepStatus;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.compensation.Aborted;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.compensation.BDone;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.compensation.CDone;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.compensation.DTried;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.compensation.DeliveryFixture;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.compensation.DeliverySaga;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.compensation.UnwindFixture;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.compensation.UnwindSaga;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * User Story 2: compensation unwinds every committed step in reverse completion order (specs/006-saga-compensation
 * spec.md US2 scenarios 1 to 3, FR-005, FR-007; tasks.md T034).
 */
class CompensationOrderTest {

    private static List<String> compensations(UnwindFixture fixture) {
        return fixture.journal().compensations().stream().filter(name -> name.startsWith("undo")).toList();
    }

    private static CompensationStatus statusOf(SagaInstance instance, String step) {
        return instance.steps().get(step).compensation().status();
    }

    @Test
    @DisplayName("2.1 steps A, B, C committed in that order are compensated C, B, A")
    void reverseOrder() {
        UnwindFixture fixture = UnwindFixture.withEveryCompensation();
        String sagaId = fixture.start("o-1");
        fixture.receive(new BDone("o-1"));
        fixture.receive(new CDone("o-1"));

        SagaHandlingResult result = fixture.receive(new Aborted("o-1"));

        assertThat(result.outcome()).isEqualTo(HandlingOutcome.COMPENSATED);
        assertThat(compensations(fixture)).containsExactly("undoC", "undoB", "undoA");
        SagaInstance instance = fixture.committed(sagaId);
        assertThat(instance.status()).isEqualTo(SagaStatus.COMPENSATED);
        for (String step : List.of(UnwindSaga.A, UnwindSaga.B, UnwindSaga.C)) {
            assertThat(statusOf(instance, step)).isEqualTo(CompensationStatus.COMPENSATED);
        }
    }

    @Test
    @DisplayName("2.1 the order is the completion order, not the declaration order")
    void completionOrderNotDeclarationOrder() {
        UnwindFixture fixture = UnwindFixture.withEveryCompensation();
        fixture.start("o-1");
        fixture.receive(new CDone("o-1"));
        fixture.receive(new BDone("o-1"));

        fixture.receive(new Aborted("o-1"));

        assertThat(compensations(fixture)).containsExactly("undoB", "undoC", "undoA");
    }

    @Test
    @DisplayName("2.2 a step without a compensation is NOTHING_TO_COMPENSATE and the saga still ends COMPENSATED")
    void nothingToCompensate() {
        UnwindFixture fixture = UnwindFixture.withoutCompensationForB();
        String sagaId = fixture.start("o-1");
        fixture.receive(new BDone("o-1"));
        fixture.receive(new CDone("o-1"));

        SagaHandlingResult result = fixture.receive(new Aborted("o-1"));

        assertThat(result.outcome()).isEqualTo(HandlingOutcome.COMPENSATED);
        assertThat(compensations(fixture)).containsExactly("undoC", "undoA");
        SagaInstance instance = fixture.committed(sagaId);
        assertThat(statusOf(instance, UnwindSaga.B)).isEqualTo(CompensationStatus.NOTHING_TO_COMPENSATE);
        assertThat(statusOf(instance, UnwindSaga.C)).isEqualTo(CompensationStatus.COMPENSATED);
    }

    @Test
    @DisplayName("2.3 a step whose unit of work rolled back is never compensated")
    void rolledBackStepIsNotCompensated() {
        UnwindFixture fixture = UnwindFixture.withEveryCompensation();
        String sagaId = fixture.start("o-1");
        fixture.receive(new BDone("o-1"));
        fixture.storage().conflictOnNextSave(sagaId);
        SagaHandlingResult lost = fixture.receive(new DTried("o-1"));
        assertThat(lost.outcome()).isEqualTo(HandlingOutcome.NOT_HANDLED);
        assertThat(fixture.journal().compensations()).contains("d");

        fixture.receive(new Aborted("o-1"));

        assertThat(compensations(fixture)).containsExactly("undoB", "undoA");
        SagaInstance instance = fixture.committed(sagaId);
        assertThat(instance.steps().get(UnwindSaga.D).status()).isEqualTo(StepStatus.NOT_RUN);
        assertThat(instance.steps().get(UnwindSaga.D).compensation()).isNull();
    }

    @Test
    @DisplayName("steps completed in one unit of work are undone in reverse execution order")
    void sameUnitOfWorkOrder() {
        UnwindFixture fixture = UnwindFixture.withEveryCompensation();
        String sagaId = fixture.start(UnwindSaga.CHAINED + "-1");
        SagaInstance afterStart = fixture.committed(sagaId);
        assertThat(afterStart.steps().get(UnwindSaga.A).completionSequence()).isEqualTo(1);
        assertThat(afterStart.steps().get(UnwindSaga.B).completionSequence()).isEqualTo(2);
        assertThat(afterStart.steps().get(UnwindSaga.C).completionSequence()).isEqualTo(3);

        fixture.receive(new Aborted(UnwindSaga.CHAINED + "-1"));

        assertThat(compensations(fixture)).containsExactly("undoC", "undoB", "undoA");
    }

    @Test
    @DisplayName("no committed step has a compensation: COMPENSATED at once, every step NOTHING_TO_COMPENSATE")
    void noCompensationAtAll() {
        DeliveryFixture fixture = new DeliveryFixture();
        String sagaId = fixture.place("o-1", 100).sagaId();

        SagaHandlingResult result = fixture.reject("o-1", "rejected before the charge");

        assertThat(result.outcome()).isEqualTo(HandlingOutcome.COMPENSATED);
        assertThat(result.unitOfWorkId()).isNotNull();
        SagaInstance instance = fixture.committed(sagaId);
        assertThat(instance.status()).isEqualTo(SagaStatus.COMPENSATED);
        assertThat(statusOf(instance, DeliverySaga.PLACED)).isEqualTo(CompensationStatus.NOTHING_TO_COMPENSATE);
        assertThat(fixture.journal().compensations()).isEmpty();
        assertThat(fixture.payments().refundIds()).isEmpty();
    }
}
