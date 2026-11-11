package io.github.gkosharovdev.sagaforge.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.core.failure.FailureClassification;
import io.github.gkosharovdev.sagaforge.core.failure.FinalFailure;
import io.github.gkosharovdev.sagaforge.core.failure.TransientFailure;
import io.github.gkosharovdev.sagaforge.core.spi.CompensationStatus;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.SagaHandlingResult;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaLifecycleEventKind;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.core.spi.StepCompensation;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.compensation.Aborted;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.compensation.BDone;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.compensation.CDone;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.compensation.DeliveryFixture;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.compensation.DeliverySaga;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.compensation.Finished;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.compensation.UnwindFixture;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.compensation.UnwindSaga;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * User Story 4: a compensation that cannot complete needs a human (specs/006-saga-compensation spec.md US4 scenarios
 * 1 and 2, FR-016, FR-017, SC-004, SC-007; tasks.md T044).
 */
class CompensationFailureTest {

    /** A refusal the provider marks final. */
    static final class RefundRefused extends RuntimeException implements FinalFailure {

        RefundRefused() {
            super("refund refused by the provider");
        }
    }

    /** A blip that would be worth retrying for a step, but compensations are never retried. */
    static final class ProviderBusy extends RuntimeException implements TransientFailure {

        ProviderBusy() {
            super("provider busy");
        }
    }

    private static List<String> compensations(UnwindFixture fixture) {
        return fixture.journal().compensations().stream().filter(name -> name.startsWith("undo")).toList();
    }

    private static UnwindFixture threeCommitted() {
        UnwindFixture fixture = UnwindFixture.withEveryCompensation();
        fixture.start("o-1");
        fixture.receive(new BDone("o-1"));
        fixture.receive(new CDone("o-1"));
        return fixture;
    }

    @Test
    @DisplayName("4.1 C compensated, B fails: A is not compensated and the saga requires intervention")
    void failedCompensationStopsUnwinding() {
        UnwindFixture fixture = threeCommitted();
        fixture.journal().failOn("undoB", new RefundRefused());

        SagaHandlingResult result = fixture.receive(new Aborted("o-1"));

        assertThat(result.outcome()).isEqualTo(HandlingOutcome.REQUIRES_INTERVENTION);
        assertThat(result.failure()).isNull();
        assertThat(compensations(fixture)).containsExactly("undoC", "undoB");
        SagaInstance instance = fixture.committed(result.sagaId());
        assertThat(instance.status()).isEqualTo(SagaStatus.REQUIRES_INTERVENTION);
        assertThat(instance.steps().get(UnwindSaga.C).compensation().status())
                .isEqualTo(CompensationStatus.COMPENSATED);
        StepCompensation failed = instance.steps().get(UnwindSaga.B).compensation();
        assertThat(failed.status()).isEqualTo(CompensationStatus.FAILED);
        assertThat(failed.failure().causeType()).isEqualTo(RefundRefused.class.getName());
        assertThat(failed.failure().classification()).isEqualTo(FailureClassification.FINAL);
        assertThat(failed.failure().attempts()).isEqualTo(1);
        assertThat(instance.steps().get(UnwindSaga.A).compensation().status())
                .isEqualTo(CompensationStatus.NOT_ATTEMPTED);
        assertThat(fixture.logger().byEvent("saga.compensation.failed")).hasSize(1);
        assertThat(fixture.storage().history(result.sagaId()).stream().map(stored -> stored.entry().kind())
                .toList()).endsWith(SagaLifecycleEventKind.COMPENSATION_STARTED,
                        SagaLifecycleEventKind.STEP_COMPENSATED, SagaLifecycleEventKind.COMPENSATION_FAILED,
                        SagaLifecycleEventKind.REQUIRES_INTERVENTION);
    }

    @Test
    @DisplayName("4.2 the stored instance alone shows the trigger, every committed step and each status with its time")
    void instanceShowsEverything() {
        UnwindFixture fixture = threeCommitted();
        fixture.journal().failOn("undoB", new RefundRefused());

        String sagaId = fixture.receive(new Aborted("o-1")).sagaId();

        SagaInstance instance = fixture.committed(sagaId);
        assertThat(instance.compensationTrigger().stepName()).isEqualTo(UnwindSaga.ABORT);
        assertThat(instance.compensationTrigger().eventType()).isEqualTo(Aborted.class.getName());
        for (String step : List.of(UnwindSaga.A, UnwindSaga.B, UnwindSaga.C)) {
            StepCompensation compensation = instance.steps().get(step).compensation();
            assertThat(compensation).as(step).isNotNull();
            assertThat(compensation.at() != null)
                    .isEqualTo(compensation.status() != CompensationStatus.NOT_ATTEMPTED);
        }
    }

    @Test
    @DisplayName("a handler failure the compensation caught still fails it: the gateway reported it")
    void caughtHandlerFailureStillFails() {
        DeliveryFixture fixture = new DeliveryFixture();
        String sagaId = fixture.placedAndCharged("o-1");
        fixture.payments().failRefundsWith(new RefundRefused());
        fixture.journal().swallowFailures(true);

        SagaHandlingResult result = fixture.reject("o-1", "no capacity");

        assertThat(result.outcome()).isEqualTo(HandlingOutcome.REQUIRES_INTERVENTION);
        assertThat(fixture.committed(sagaId).steps().get(DeliverySaga.CHARGED).compensation().failure().causeType())
                .isEqualTo(RefundRefused.class.getName());
    }

    @Test
    @DisplayName("a transient cause is not retried either: one call, no pause, recorded EXHAUSTED after one attempt")
    void transientCauseIsNotRetried() {
        DeliveryFixture fixture = new DeliveryFixture();
        String sagaId = fixture.placedAndCharged("o-1");
        fixture.payments().failRefundsWith(new ProviderBusy());

        SagaHandlingResult result = fixture.reject("o-1", "no capacity");

        assertThat(result.outcome()).isEqualTo(HandlingOutcome.REQUIRES_INTERVENTION);
        assertThat(fixture.payments().refundIds()).hasSize(1);
        assertThat(fixture.pause().waits()).isEmpty();
        StepCompensation failed = fixture.committed(sagaId).steps().get(DeliverySaga.CHARGED).compensation();
        assertThat(failed.failure().classification()).isEqualTo(FailureClassification.EXHAUSTED);
        assertThat(failed.failure().attempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("an unmarked failure is recorded UNCLASSIFIED")
    void unmarkedFailureIsUnclassified() {
        UnwindFixture fixture = threeCommitted();
        fixture.journal().failOn("undoC", new IllegalStateException("bug"));

        String sagaId = fixture.receive(new Aborted("o-1")).sagaId();

        assertThat(fixture.committed(sagaId).steps().get(UnwindSaga.C).compensation().failure().classification())
                .isEqualTo(FailureClassification.UNCLASSIFIED);
        assertThat(compensations(fixture)).containsExactly("undoC");
    }

    @Test
    @DisplayName("a later event for an instance that requires intervention is UNEXPECTED and runs nothing")
    void noLaterRetry() {
        UnwindFixture fixture = threeCommitted();
        fixture.journal().failOn("undoB", new RefundRefused());
        String sagaId = fixture.receive(new Aborted("o-1")).sagaId();
        fixture.journal().clearFailures();

        SagaHandlingResult later = fixture.receive(new Finished("o-1"));

        assertThat(later.outcome()).isEqualTo(HandlingOutcome.UNEXPECTED);
        assertThat(compensations(fixture)).containsExactly("undoC", "undoB");
        assertThat(fixture.committed(sagaId).status()).isEqualTo(SagaStatus.REQUIRES_INTERVENTION);
    }
}
