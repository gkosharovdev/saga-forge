package io.github.gkosharovdev.sagaforge.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.gkosharovdev.sagaforge.core.command.CommandId;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.test.fixtures.Shop.OrderPlaced;
import io.github.gkosharovdev.sagaforge.test.fixtures.Shop.PaymentTaken;
import io.github.gkosharovdev.sagaforge.test.fixtures.Shop.ReserveStock;
import io.github.gkosharovdev.sagaforge.test.fixtures.Shop.ShopSaga;
import io.github.gkosharovdev.sagaforge.test.fixtures.Shop.StockReserved;
import io.github.gkosharovdev.sagaforge.test.fixtures.Shop.TakePayment;
import io.github.gkosharovdev.sagaforge.test.fixtures.Shop;

/** User Story 1: how a saga reacts to an event (specs/009-saga-test-fixture tasks.md T014). */
class FixtureApiTest {

    private final SagaFixture fixture = SagaFixture.of(ShopSaga.class).build();

    @Test
    @DisplayName("scenario 1: a start event creates an instance and reports its commands and status")
    void startEvent() {
        fixture.when(new OrderPlaced("o-1", "10"))
                .expectStarted()
                .expectOutcome(HandlingOutcome.WAITING)
                .expectCommands(new TakePayment("o-1", "10"))
                .expectCompletedStep(Shop.PLACED)
                .expectContext("amount", "10")
                .expectStatus(SagaStatus.RUNNING);

        assertThat(fixture.instance(ShopSaga.class, "o-1").sagaId()).isEqualTo("saga-1");
        assertThat(fixture.commands()).singleElement().satisfies(command -> {
            assertThat(command.commandId()).isEqualTo(CommandId.of("saga-1", Shop.PLACED,
                    TakePayment.class.getName(), 1));
            assertThat(command.handled()).isFalse();
            assertThat(command.step()).isEqualTo(Shop.PLACED);
        });
    }

    @Test
    @DisplayName("scenario 2: given events reach the same instance and do not count")
    void givenHistoryDoesNotCount() {
        fixture.given(new OrderPlaced("o-1", "10"))
                .when(new PaymentTaken("payment-1", "o-1", "10"))
                .expectCommands(new ReserveStock("o-1"))
                .expectContext("paymentId", "payment-1")
                .expectCompletedStep(Shop.PAID)
                .expectStatus(SagaStatus.RUNNING);
    }

    @Test
    @DisplayName("scenario 3: a different set, order or content of commands fails")
    void differentCommandsFail() {
        Then then = fixture.when(new OrderPlaced("o-1", "10"));

        assertThatThrownBy(() -> then.expectCommands(new TakePayment("o-1", "11")))
                .isInstanceOf(SagaExpectationError.class).isInstanceOf(AssertionError.class)
                .hasMessageContaining("expected commands: [TakePayment[orderId=o-1, amount=11]]")
                .hasMessageContaining("dispatched:        [TakePayment[orderId=o-1, amount=10]]");
        assertThatThrownBy(() -> then.expectCommands(new TakePayment("o-1", "10"), new ReserveStock("o-1")))
                .isInstanceOf(SagaExpectationError.class).hasMessageContaining("expected 2 commands, dispatched 1");
        assertThatThrownBy(() -> then.expectCommands()).isInstanceOf(SagaExpectationError.class);
    }

    @Test
    @DisplayName("scenario 4: expectNoCommands fails naming the dispatched command")
    void noCommandsFails() {
        assertThatThrownBy(() -> fixture.when(new OrderPlaced("o-1", "10")).expectNoCommands())
                .isInstanceOf(SagaExpectationError.class).hasMessageContaining("TakePayment[orderId=o-1, amount=10]");
    }

    @Test
    @DisplayName("scenario 5: an @EndSaga step ends the instance with that step as its outcome")
    void endStep() {
        fixture.given(new OrderPlaced("o-1", "10"), new PaymentTaken("payment-1", "o-1", "10"))
                .when(new StockReserved("o-1"))
                .expectOutcome(HandlingOutcome.COMPLETED)
                .expectEndedWith(Shop.RESERVED)
                .expectNoCommands()
                .expectStatus(SagaStatus.COMPLETED);
    }

    @Test
    @DisplayName("scenario 6: context expectations name the key, the expected and the actual value")
    void contextExpectations() {
        Then then = fixture.when(new OrderPlaced("o-1", "10"));

        then.expectContext("amount", "10").expectNoContext("paymentId");
        assertThatThrownBy(() -> then.expectContext("amount", 10))
                .isInstanceOf(SagaExpectationError.class)
                .hasMessageContaining("expected SagaContext 'amount': 10 (Integer)")
                .hasMessageContaining("actual:                        10 (String)");
        assertThatThrownBy(() -> then.expectContext("paymentId", "p")).hasMessageContaining("absent");
        assertThatThrownBy(() -> then.expectNoContext("amount")).hasMessageContaining("10 (String)");
    }

    @Test
    @DisplayName("matchers, the last given event as subject, and reuse of a fixture across events")
    void matchersAndReuse() {
        fixture.when(new OrderPlaced("o-1", "10"))
                .expectCommandsMatching(command -> command instanceof TakePayment)
                .expectCommand(TakePayment.class, payment -> payment.amount().equals("10"));

        fixture.given(new PaymentTaken("payment-1", "o-1", "10")).then()
                .expectCommands(new ReserveStock("o-1"));
        fixture.when(new StockReserved("o-1")).expectStatus(SagaStatus.COMPLETED);

        assertThatThrownBy(() -> fixture.when(new OrderPlaced("o-2", "5"))
                .expectCommand(ReserveStock.class, reserve -> true)).isInstanceOf(SagaExpectationError.class);
        assertThat(fixture.instance(ShopSaga.class, "o-2").sagaId()).isEqualTo("saga-2");
        assertThat(fixture.commands()).hasSize(3);
    }

    @Test
    @DisplayName("a then() without any given event is misuse")
    void thenWithoutGiven() {
        assertThatThrownBy(() -> fixture.given().then()).isInstanceOf(IllegalStateException.class);
    }
}
