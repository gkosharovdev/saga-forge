package io.github.gkosharovdev.sagaforge.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.core.compensation.CompensationTrigger;
import io.github.gkosharovdev.sagaforge.core.compensation.CompensationTriggerKind;
import io.github.gkosharovdev.sagaforge.core.failure.FailureClassification;
import io.github.gkosharovdev.sagaforge.core.spi.CompensationStatus;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.SagaHandlingResult;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaLifecycleEventKind;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.core.spi.StepStatus;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.compensation.CourierBusy;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.compensation.CourierUnavailable;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.compensation.DeliveryFixture;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.compensation.DeliverySaga;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * User Story 3: a technical failure after earlier commits leads to compensation (specs/006-saga-compensation spec.md
 * US3 scenarios 1 to 3, FR-003, FR-006 to FR-006b; tasks.md T040).
 */
class FinalFailureCompensationTest {

    private final DeliveryFixture fixture = new DeliveryFixture();

    @Test
    @DisplayName("3.1 a FinalFailure after the committed payment rolls the step back and refunds the payment")
    void finalFailureCompensates() {
        String sagaId = fixture.placedAndCharged("o-1");
        fixture.couriers().failWith(new CourierUnavailable());

        SagaHandlingResult result = fixture.requestCourier("o-1");

        assertThat(result.outcome()).isEqualTo(HandlingOutcome.COMPENSATED);
        assertThat(fixture.couriers().calls()).isEqualTo(1);
        assertThat(fixture.pause().waits()).isEmpty();
        assertThat(fixture.payments().refunds()).hasSize(1);
        SagaInstance instance = fixture.committed(sagaId);
        assertThat(instance.status()).isEqualTo(SagaStatus.COMPENSATED);
        assertThat(instance.failure()).isNull();
        assertThat(instance.steps().get(DeliverySaga.COURIER).status()).isEqualTo(StepStatus.NOT_RUN);
        CompensationTrigger trigger = instance.compensationTrigger();
        assertThat(trigger.kind()).isEqualTo(CompensationTriggerKind.FINAL_FAILURE);
        assertThat(trigger.stepName()).isEqualTo(DeliverySaga.COURIER);
        assertThat(trigger.failure().causeType()).isEqualTo(CourierUnavailable.class.getName());
        assertThat(trigger.failure().message()).isEqualTo("courier service permanently unavailable");
        assertThat(trigger.failure().classification()).isEqualTo(FailureClassification.FINAL);
        assertThat(fixture.journal().triggers()).containsExactly(trigger);
        assertThat(fixture.historyKinds(sagaId)).endsWith(SagaLifecycleEventKind.COMPENSATION_STARTED,
                SagaLifecycleEventKind.STEP_COMPENSATED, SagaLifecycleEventKind.COMPENSATED);
    }

    @Test
    @DisplayName("3.2 a failure that is not marked final ends FAILED as before and compensates nothing")
    void unclassifiedFailureFails() {
        String sagaId = fixture.placedAndCharged("o-1");
        fixture.couriers().failWith(new IllegalStateException("bug in the courier adapter"));

        SagaHandlingResult result = fixture.requestCourier("o-1");

        assertThat(result.outcome()).isEqualTo(HandlingOutcome.FAILED);
        assertThat(result.failure().classification()).isEqualTo(FailureClassification.UNCLASSIFIED);
        assertThat(fixture.committed(sagaId).status()).isEqualTo(SagaStatus.FAILED);
        assertThat(fixture.committed(sagaId).compensationTrigger()).isNull();
        assertThat(fixture.payments().refundIds()).isEmpty();
        assertThat(fixture.journal().compensations()).isEmpty();
    }

    @Test
    @DisplayName("3.3 a transient failure on every allowed attempt compensates after the last, trigger RETRIES_EXHAUSTED")
    void exhaustedRetriesCompensate() {
        String sagaId = fixture.placedAndCharged("o-1");
        fixture.couriers().failWith(new CourierBusy());

        SagaHandlingResult result = fixture.requestCourier("o-1");

        assertThat(result.outcome()).isEqualTo(HandlingOutcome.COMPENSATED);
        assertThat(fixture.couriers().calls()).isEqualTo(3);
        assertThat(fixture.pause().waits()).hasSize(2);
        CompensationTrigger trigger = fixture.committed(sagaId).compensationTrigger();
        assertThat(trigger.kind()).isEqualTo(CompensationTriggerKind.RETRIES_EXHAUSTED);
        assertThat(trigger.failure().attempts()).isEqualTo(3);
        assertThat(trigger.failure().causeType()).isEqualTo(CourierBusy.class.getName());
        assertThat(fixture.committed(sagaId).pendingRetry()).isNull();
        assertThat(fixture.payments().refunds()).hasSize(1);
        assertThat(fixture.historyKinds(sagaId)).contains(SagaLifecycleEventKind.ATTEMPT_FAILED);
    }

    @Test
    @DisplayName("a FinalFailure in the start step ends FAILED FINAL: nothing committed to compensate")
    void startStepFinalFailureFails() {
        fixture.payments().failChargesWith(new CourierUnavailable());

        SagaHandlingResult result = fixture.place("o-1", 100);

        assertThat(result.outcome()).isEqualTo(HandlingOutcome.FAILED);
        assertThat(result.failure().classification()).isEqualTo(FailureClassification.FINAL);
        assertThat(fixture.committed(result.sagaId()).status()).isEqualTo(SagaStatus.FAILED);
        assertThat(fixture.committed(result.sagaId()).compensationTrigger()).isNull();
        assertThat(fixture.journal().compensations()).isEmpty();
    }

    @Test
    @DisplayName("the refund step's compensation status is COMPENSATED and the start step NOTHING_TO_COMPENSATE")
    void stepStatusesAfterFinalFailure() {
        String sagaId = fixture.placedAndCharged("o-1");
        fixture.couriers().failWith(new CourierUnavailable());

        fixture.requestCourier("o-1");

        SagaInstance instance = fixture.committed(sagaId);
        assertThat(instance.steps().get(DeliverySaga.CHARGED).compensation().status())
                .isEqualTo(CompensationStatus.COMPENSATED);
        assertThat(instance.steps().get(DeliverySaga.PLACED).compensation().status())
                .isEqualTo(CompensationStatus.NOTHING_TO_COMPENSATE);
        assertThat(instance.steps().get(DeliverySaga.COURIER).compensation()).isNull();
    }
}
