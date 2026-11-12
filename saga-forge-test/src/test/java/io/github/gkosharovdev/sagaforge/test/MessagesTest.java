package io.github.gkosharovdev.sagaforge.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.gkosharovdev.sagaforge.core.failure.FailureClassification;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.runtime.SagaRuntimeStartupException;
import io.github.gkosharovdev.sagaforge.test.fixtures.BrokenSaga;
import io.github.gkosharovdev.sagaforge.test.fixtures.Shop;
import io.github.gkosharovdev.sagaforge.test.fixtures.Shop.OrderPlaced;
import io.github.gkosharovdev.sagaforge.test.fixtures.Shop.PaymentTaken;
import io.github.gkosharovdev.sagaforge.test.fixtures.Shop.ReserveStock;
import io.github.gkosharovdev.sagaforge.test.fixtures.Shop.ShopSaga;

/** User Story 6 and SC-004: failures explain themselves (specs/009-saga-test-fixture tasks.md T037). */
class MessagesTest {

    private static final String HEADER = "ShopSaga[orderId=o-1] (saga-1), when PaymentTaken[paymentId=payment-1,"
            + " orderId=o-1, amount=10]:";

    private Then paid() {
        SagaFixture fixture = SagaFixture.of(ShopSaga.class).build();
        return fixture.given(new OrderPlaced("o-1", "10")).when(new PaymentTaken("payment-1", "o-1", "10"));
    }

    private static SagaExpectationError failureOf(Then then, Consumer<Then> expectation) {
        SagaExpectationError error = catchThrowableOfType(SagaExpectationError.class, () -> expectation.accept(then));
        assertThat(error).as("the expectation should fail").isNotNull();
        return error;
    }

    @Test
    @DisplayName("SC-004: every failing expectation names saga type, association value, event and the values")
    void everyExpectationNamesItsSituation() {
        List<Consumer<Then>> failing = List.of(
                then -> then.expectCommands(new ReserveStock("o-2")),
                then -> then.expectCommandsMatching(command -> false),
                Then::expectNoCommands,
                then -> then.expectCommand(Shop.TakePayment.class, payment -> true),
                Then::expectUnhandled,
                then -> then.expectOutcome(HandlingOutcome.COMPLETED),
                Then::expectStarted,
                then -> then.expectCompletedStep(Shop.RESERVED),
                then -> then.expectCompensations(Shop.REFUND),
                then -> then.expectAttempts(Shop.PAID, 2),
                then -> then.expectStatus(SagaStatus.COMPLETED),
                then -> then.expectEndedWith(Shop.RESERVED),
                then -> then.expectCompensatedAfter(Shop.MISSING),
                then -> then.expectFailure(IllegalStateException.class, FailureClassification.FINAL),
                then -> then.expectContext("paymentId", "payment-2"),
                then -> then.expectNoContext("paymentId"));

        for (Consumer<Then> expectation : failing) {
            SagaExpectationError error = failureOf(paid(), expectation);
            assertThat(error.getMessage())
                    .startsWith(HEADER)
                    .contains("step paymentTaken completed (attempt 1), status RUNNING")
                    .containsPattern("(expected|the event)");
        }
        assertThat(failureOf(paid(), then -> then.expectContext("paymentId", "payment-2")).getMessage())
                .contains("payment-2").contains("payment-1");
        assertThat(failureOf(paid(), then -> then.expectStatus(SagaStatus.COMPLETED)).getMessage())
                .contains("expected status: COMPLETED").contains("RUNNING");
    }

    @Test
    @DisplayName("scenario 1: the command block lists expected and dispatched commands with their content")
    void commandBlock() {
        SagaFixture fixture = SagaFixture.of(ShopSaga.class).build();
        Then then = fixture.given(new OrderPlaced("o-1", "10"), new PaymentTaken("payment-1", "o-1", "10"))
                .when(new Shop.StockReserved("o-1"));

        SagaExpectationError error = failureOf(then, t -> t.expectCommands(new ReserveStock("o-1")));

        assertThat(error.getMessage()).isEqualTo("""
                ShopSaga[orderId=o-1] (saga-1), when StockReserved[orderId=o-1]:
                  step stockReserved completed (attempt 1), status COMPLETED (stockReserved), outcome COMPLETED
                  expected commands: [ReserveStock[orderId=o-1]]
                  dispatched:        []
                  expected 1 command, dispatched none""");
    }

    @Test
    @DisplayName("scenario 2: a step that threw names the step, the exception and the status, with the cause")
    void unexpectedFailureIsTheCause() {
        SagaFixture fixture = SagaFixture.of(ShopSaga.class).build();
        Then then = fixture.given(new OrderPlaced("o-1", "10")).when(new PaymentTaken("payment-1", "o-1", "99"));

        SagaExpectationError error = failureOf(then, t -> t.expectStatus(SagaStatus.RUNNING));

        assertThat(error.getMessage())
                .contains("status FAILED")
                .contains("failure: step paymentTaken, java.lang.IllegalStateException: payment of 99 does not match 10,"
                        + " UNCLASSIFIED, 1 attempt");
        assertThat(error.getCause()).isInstanceOf(IllegalStateException.class)
                .hasMessage("payment of 99 does not match 10");
    }

    @Test
    @DisplayName("an event that reached no instance says so")
    void noInstance() {
        SagaFixture fixture = SagaFixture.of(ShopSaga.class).build();

        SagaExpectationError error = failureOf(fixture.when(new PaymentTaken("p", "o-x", "1")),
                t -> t.expectStatus(SagaStatus.RUNNING));

        assertThat(error.getMessage()).contains("when PaymentTaken[paymentId=p, orderId=o-x, amount=1]:")
                .contains("ShopSaga: UNMATCHED").contains("the event reached no saga instance");
    }

    @Test
    @DisplayName("scenario 3: an invalid saga fails setup with every model error, as at runtime startup")
    void invalidModel() {
        SagaRuntimeStartupException refused = catchThrowableOfType(SagaRuntimeStartupException.class,
                () -> SagaFixture.of(BrokenSaga.class).build());

        assertThat(refused).isNotNull();
        assertThat(refused.report().allErrors()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(refused.getMessage()).contains("BrokenSaga");
    }
}
