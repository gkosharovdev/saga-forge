package io.github.gkosharovdev.sagaforge.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.core.compensation.CompensationTrigger;
import io.github.gkosharovdev.sagaforge.core.compensation.CompensationTriggerKind;
import io.github.gkosharovdev.sagaforge.core.spi.CompensationStatus;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.SagaHandlingResult;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaLifecycleEventKind;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.compensation.DeliveryFixture;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.compensation.DeliveryRejected;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.compensation.DeliverySaga;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.compensation.Refund;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.compensation.StockUnavailable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * User Story 1: a business rejection after a committed payment refunds it (specs/006-saga-compensation spec.md US1
 * scenarios 1 to 4, FR-001, FR-002, FR-004, FR-009 to FR-011, FR-013 to FR-015; tasks.md T031).
 */
class BusinessFailureCompensationTest {

    private final DeliveryFixture fixture = new DeliveryFixture();

    @Test
    @DisplayName("1.1 a rejection after the committed payment dispatches exactly one refund and ends COMPENSATED")
    void rejectionRefundsOnce() {
        String sagaId = fixture.placedAndCharged("o-1");

        SagaHandlingResult result = fixture.reject("o-1", "address not served");

        assertThat(result.outcome()).isEqualTo(HandlingOutcome.COMPENSATED);
        assertThat(result.outcomeStep()).isNull();
        assertThat(result.failure()).isNull();
        assertThat(fixture.payments().refunds()).containsExactly(new Refund(sagaId, "pay-o-1", 100));

        SagaInstance instance = fixture.committed(sagaId);
        assertThat(instance.status()).isEqualTo(SagaStatus.COMPENSATED);
        CompensationTrigger trigger = instance.compensationTrigger();
        assertThat(trigger.kind()).isEqualTo(CompensationTriggerKind.FAILURE_OUTCOME);
        assertThat(trigger.stepName()).isEqualTo(DeliverySaga.REJECTED);
        assertThat(trigger.eventType()).isEqualTo(DeliveryRejected.class.getName());
        assertThat(instance.steps().get(DeliverySaga.CHARGED).compensation().status())
                .isEqualTo(CompensationStatus.COMPENSATED);
        assertThat(instance.steps().get(DeliverySaga.PLACED).compensation().status())
                .isEqualTo(CompensationStatus.NOTHING_TO_COMPENSATE);
        assertThat(instance.steps().get(DeliverySaga.REJECTED).compensation()).isNull();
        assertThat(instance.steps().get(DeliverySaga.ACCEPTED).compensation()).isNull();
        assertThat(fixture.contexts().find(sagaId).orElseThrow().values())
                .containsEntry("rejectionReason", "address not served");
    }

    @Test
    @DisplayName("1.1 the compensation receives the persisted trigger and its refund identity is scoped to it")
    void compensationSeesPersistedTrigger() {
        String sagaId = fixture.placedAndCharged("o-1");

        fixture.reject("o-1", "no capacity");

        assertThat(fixture.journal().compensations()).containsExactly(DeliverySaga.REFUND);
        assertThat(fixture.journal().triggers()).containsExactly(fixture.committed(sagaId).compensationTrigger());
        assertThat(fixture.payments().refundIds()).singleElement()
                .satisfies(id -> {
                    assertThat(id.stepName()).isEqualTo(DeliverySaga.REFUND);
                    assertThat(id.sagaId()).isEqualTo(sagaId);
                    assertThat(id.ordinal()).isEqualTo(1);
                });
    }

    @Test
    @DisplayName("1.1 lifecycle history: the trigger step, COMPENSATION_STARTED, STEP_COMPENSATED, COMPENSATED")
    void lifecycleHistory() {
        String sagaId = fixture.placedAndCharged("o-1");

        fixture.reject("o-1", "no capacity");

        assertThat(fixture.historyKinds(sagaId)).containsExactly(
                SagaLifecycleEventKind.CREATED,
                SagaLifecycleEventKind.STEP_COMPLETED,
                SagaLifecycleEventKind.STEP_COMPLETED,
                SagaLifecycleEventKind.STEP_COMPLETED,
                SagaLifecycleEventKind.COMPENSATION_STARTED,
                SagaLifecycleEventKind.STEP_COMPENSATED,
                SagaLifecycleEventKind.COMPENSATED);
        assertThat(fixture.logger().byEvent("saga.compensation.started")).hasSize(1);
        assertThat(fixture.logger().byEvent("saga.compensation.ended")).hasSize(1);
    }

    @Test
    @DisplayName("1.2 a stock shortage compensates the same way and records its own trigger")
    void stockShortageRecordsItsTrigger() {
        String sagaId = fixture.placedAndCharged("o-1");

        SagaHandlingResult result = fixture.stockUnavailable("o-1");

        assertThat(result.outcome()).isEqualTo(HandlingOutcome.COMPENSATED);
        assertThat(fixture.payments().refunds()).hasSize(1);
        CompensationTrigger trigger = fixture.committed(sagaId).compensationTrigger();
        assertThat(trigger.stepName()).isEqualTo(DeliverySaga.STOCK);
        assertThat(trigger.eventType()).isEqualTo(StockUnavailable.class.getName());
    }

    @Test
    @DisplayName("1.3 an accepted delivery completes the saga and runs no compensation")
    void acceptanceCompletes() {
        String sagaId = fixture.placedAndCharged("o-1");

        SagaHandlingResult result = fixture.accept("o-1");

        assertThat(result.outcome()).isEqualTo(HandlingOutcome.COMPLETED);
        assertThat(fixture.committed(sagaId).status()).isEqualTo(SagaStatus.COMPLETED);
        assertThat(fixture.committed(sagaId).compensationTrigger()).isNull();
        assertThat(fixture.payments().refunds()).isEmpty();
        assertThat(fixture.journal().compensations()).isEmpty();
    }

    @Test
    @DisplayName("1.4 a second rejection after COMPENSATED is DUPLICATE and dispatches no second refund")
    void secondRejectionIsDuplicate() {
        fixture.placedAndCharged("o-1");
        fixture.reject("o-1", "no capacity");

        SagaHandlingResult again = fixture.reject("o-1", "no capacity");

        assertThat(again.outcome()).isEqualTo(HandlingOutcome.DUPLICATE);
        assertThat(fixture.payments().refundIds()).hasSize(1);
        assertThat(fixture.journal().compensations()).hasSize(1);
    }

    @Test
    @DisplayName("a late forward event after COMPENSATED is UNEXPECTED and runs nothing")
    void lateForwardEventAfterEnd() {
        String sagaId = fixture.placedAndCharged("o-1");
        fixture.reject("o-1", "no capacity");

        SagaHandlingResult late = fixture.accept("o-1");

        assertThat(late.outcome()).isEqualTo(HandlingOutcome.UNEXPECTED);
        assertThat(fixture.committed(sagaId).status()).isEqualTo(SagaStatus.COMPENSATED);
    }
}
