package io.github.gkosharovdev.sagaforge.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.gkosharovdev.sagaforge.core.failure.FailureClassification;
import io.github.gkosharovdev.sagaforge.core.outcome.FailurePhase;
import io.github.gkosharovdev.sagaforge.core.outcome.SagaCompensated;
import io.github.gkosharovdev.sagaforge.core.outcome.SagaCompleted;
import io.github.gkosharovdev.sagaforge.core.outcome.SagaFailed;
import io.github.gkosharovdev.sagaforge.core.compensation.CompensationTriggerKind;
import io.github.gkosharovdev.sagaforge.test.fixtures.AuditSaga;
import io.github.gkosharovdev.sagaforge.test.fixtures.Shop;
import io.github.gkosharovdev.sagaforge.test.fixtures.Shop.OrderPlaced;
import io.github.gkosharovdev.sagaforge.test.fixtures.Shop.PaymentTaken;
import io.github.gkosharovdev.sagaforge.test.fixtures.Shop.ShopSaga;
import io.github.gkosharovdev.sagaforge.test.fixtures.Shop.StockMissing;
import io.github.gkosharovdev.sagaforge.test.fixtures.Shop.StockReserved;

/** Outcome-event expectations of the fixture ({@code specs/011-saga-outcome-events} FR-018, SC-007, research §10). */
class OutcomeEventExpectationsTest {

    private static SagaFixture paid(String orderId) {
        SagaFixture fixture = SagaFixture.of(ShopSaga.class).build();
        fixture.given(new OrderPlaced(orderId, "10"), new PaymentTaken("payment-" + orderId, orderId, "10"));
        return fixture;
    }

    @Test
    @DisplayName("a completed saga yields one SagaCompleted naming its end step")
    void completed() {
        paid("o-1").when(new StockReserved("o-1"))
                .expectOutcomeEvent(SagaCompleted.class, completed -> completed.outcomeStep().equals(Shop.RESERVED))
                .expectOutcomeEvent(SagaCompleted.class);
    }

    @Test
    @DisplayName("a compensated saga yields one SagaCompensated with its trigger")
    void compensated() {
        Then then = paid("o-2").when(new StockMissing("o-2"))
                .expectOutcomeEvent(SagaCompensated.class, compensated ->
                        compensated.trigger().kind() == CompensationTriggerKind.FAILURE_OUTCOME
                                && compensated.trigger().stepName().equals(Shop.MISSING));

        assertThat(then.result().outcomeEvents()).singleElement()
                .satisfies(outcome -> assertThat(outcome.associations()).containsEntry("orderId", "o-2"));
    }

    @Test
    @DisplayName("an unclassified failure yields one SagaFailed in the step phase")
    void failed() {
        SagaFixture fixture = SagaFixture.of(ShopSaga.class).build();
        fixture.given(new OrderPlaced("o-3", "10"));

        fixture.when(new PaymentTaken("p", "o-3", "99"))
                .expectOutcomeEvent(SagaFailed.class, failed -> failed.phase() == FailurePhase.STEP
                        && failed.classification() == FailureClassification.UNCLASSIFIED
                        && failed.failedStep().equals(Shop.PAID));
    }

    @Test
    @DisplayName("a waiting saga yields none; expectOutcomeEvent then fails, listing what was committed")
    void noneWhileWaiting() {
        Then then = SagaFixture.of(ShopSaga.class).build().when(new OrderPlaced("o-4", "10"))
                .expectNoOutcomeEvent();

        assertThatThrownBy(() -> then.expectOutcomeEvent(SagaCompleted.class))
                .isInstanceOf(SagaExpectationError.class)
                .hasMessageContaining("expected one outcome event: SagaCompleted matching the predicate")
                .hasMessageMatching("(?s).*committed: +\\[\\].*");
    }

    @Test
    @DisplayName("a wrong type or a non-matching predicate fails, listing the event")
    void mismatch() {
        Then then = paid("o-5").when(new StockReserved("o-5"));

        assertThatThrownBy(() -> then.expectOutcomeEvent(SagaFailed.class))
                .isInstanceOf(SagaExpectationError.class).hasMessageContaining("SagaCompleted[");
        assertThatThrownBy(() -> then.expectOutcomeEvent(SagaCompleted.class, completed -> false))
                .isInstanceOf(SagaExpectationError.class).hasMessageContaining("associations={orderId=o-5}");
        assertThatThrownBy(then::expectNoOutcomeEvent)
                .isInstanceOf(SagaExpectationError.class).hasMessageContaining("SagaCompleted[");
    }

    @Test
    @DisplayName("two outcome events fail expectOutcomeEvent unless an instance is chosen")
    void twoOutcomes() {
        SagaFixture fixture = SagaFixture.of(ShopSaga.class, AuditSaga.class).build();
        fixture.given(new OrderPlaced("o-6", "10"), new PaymentTaken("payment-o-6", "o-6", "10"));

        Then then = fixture.when(new StockReserved("o-6"));

        assertThat(then.result().outcomeEvents()).hasSize(2);
        assertThatThrownBy(() -> then.expectOutcomeEvent(SagaCompleted.class))
                .isInstanceOf(SagaExpectationError.class).hasMessageContaining(ShopSaga.class.getName())
                .hasMessageContaining(AuditSaga.class.getName());
        then.instance(ShopSaga.class, "o-6").expectOutcomeEvent(SagaCompleted.class,
                completed -> completed.sagaType().equals(ShopSaga.class.getName()));
        then.instance(AuditSaga.class, "o-6").expectOutcomeEvent(SagaCompleted.class,
                completed -> completed.outcomeStep().equals("closed"));
    }

    @Test
    @DisplayName("outcomeEvents(false): no outcome event is recorded")
    void switchedOff() {
        SagaFixture fixture = SagaFixture.of(ShopSaga.class).outcomeEvents(false).build();
        fixture.given(new OrderPlaced("o-8", "10"), new PaymentTaken("payment-o-8", "o-8", "10"));

        fixture.when(new StockReserved("o-8")).expectNoOutcomeEvent();
    }
}
