package io.github.gkosharovdev.sagaforge.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.gkosharovdev.sagaforge.core.spi.HandlingOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.test.fixtures.AuditSaga;
import io.github.gkosharovdev.sagaforge.test.fixtures.Shop;
import io.github.gkosharovdev.sagaforge.test.fixtures.Shop.OrderPlaced;
import io.github.gkosharovdev.sagaforge.test.fixtures.Shop.OrderViewed;
import io.github.gkosharovdev.sagaforge.test.fixtures.Shop.PaymentDesk;
import io.github.gkosharovdev.sagaforge.test.fixtures.Shop.PaymentTaken;
import io.github.gkosharovdev.sagaforge.test.fixtures.Shop.ReserveStock;
import io.github.gkosharovdev.sagaforge.test.fixtures.Shop.ShopOrder;
import io.github.gkosharovdev.sagaforge.test.fixtures.Shop.ShopSaga;
import io.github.gkosharovdev.sagaforge.test.fixtures.Shop.StockReserved;

/** User Story 3: correlation and duplicates (specs/009-saga-test-fixture tasks.md T033). */
class CorrelationTest {

    private final SagaFixture fixture = SagaFixture.of(ShopSaga.class).build();

    @Test
    @DisplayName("scenario 1: only the addressed instance advances; each is reachable by its association value")
    void onlyOneAdvances() {
        fixture.given(new OrderPlaced("o-3", "10"), new OrderPlaced("o-4", "20"))
                .when(new PaymentTaken("payment-4", "o-4", "20"))
                .expectCommands(new ReserveStock("o-4"))
                .expectContext("paymentId", "payment-4")
                .instance(ShopSaga.class, "o-3")
                .expectNoCommands()
                .expectNoContext("paymentId")
                .expectStatus(SagaStatus.RUNNING);

        assertThat(fixture.instance(ShopSaga.class, "o-3").steps().get(Shop.PAID).status().name())
                .isEqualTo("NOT_RUN");
        assertThat(fixture.instance(ShopSaga.class, "o-4").context()).containsEntry("paymentId", "payment-4");
        assertThatThrownBy(() -> fixture.instance(ShopSaga.class, "o-404"))
                .isInstanceOf(SagaExpectationError.class).hasMessageContaining("o-404");
    }

    @Test
    @DisplayName("scenario 2: an event no step accepts, or no instance correlates with, is unhandled")
    void unhandled() {
        fixture.when(new OrderViewed("o-1")).expectUnhandled().expectNoCommands();
        assertThatThrownBy(() -> fixture.when(new OrderViewed("o-1")).expectHandled())
                .isInstanceOf(SagaExpectationError.class).hasMessageContaining("no saga type reacts to it");

        fixture.when(new PaymentTaken("payment-1", "o-unknown", "10"))
                .expectUnhandled()
                .expectOutcome(HandlingOutcome.UNMATCHED);
    }

    @Test
    @DisplayName("scenario 3: a redelivered event of a completed step is a duplicate and dispatches nothing")
    void duplicate() {
        fixture.given(new OrderPlaced("o-4", "20"), new PaymentTaken("payment-4", "o-4", "20"))
                .when(new PaymentTaken("payment-4", "o-4", "20"))
                .expectOutcome(HandlingOutcome.DUPLICATE)
                .expectNoCommands()
                .expectHandled();
    }

    @Test
    @DisplayName("scenario 4: a second start for an active association value is a duplicate start")
    void duplicateStart() {
        fixture.given(new OrderPlaced("o-4", "20"))
                .when(new OrderPlaced("o-4", "20"))
                .expectOutcome(HandlingOutcome.DUPLICATE_START)
                .expectNoCommands();
    }

    @Test
    @DisplayName("several saga types: each instance is reported, and the subject must be chosen")
    void severalSagaTypes() {
        SagaFixture both = SagaFixture.of(ShopSaga.class, AuditSaga.class).build();

        Then then = both.when(new OrderPlaced("o-1", "10"));

        assertThat(then.result().instances()).hasSize(2);
        assertThatThrownBy(() -> then.expectStatus(SagaStatus.RUNNING))
                .isInstanceOf(SagaExpectationError.class).hasMessageContaining("touched 2 instances");
        then.instance(AuditSaga.class, "o-1").expectStarted().expectNoCommands().expectStatus(SagaStatus.RUNNING);
        then.instance(ShopSaga.class, "o-1").expectStarted().expectCommands(new Shop.TakePayment("o-1", "10"));
    }

    @Test
    @DisplayName("G-ENG-24: an event a step of its own instance runs is not handed to other saga types")
    void eventRunInProcessStaysWithItsInstance() {
        SagaFixture both = SagaFixture.of(ShopSaga.class, AuditSaga.class).handlers(new PaymentDesk())
                .register(ShopOrder.class).build();

        both.givenAggregates(ShopOrder.create("o-1", true))
                .when(new OrderPlaced("o-1", "10"))
                .instance(ShopSaga.class, "o-1").expectEndedWith(Shop.RESERVED)
                .instance(AuditSaga.class, "o-1").expectStatus(SagaStatus.RUNNING);

        both.when(new StockReserved("o-1"))
                .instance(AuditSaga.class, "o-1").expectEndedWith("closed");
    }
}
