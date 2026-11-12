package io.github.gkosharovdev.sagaforge.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.gkosharovdev.sagaforge.core.failure.FailureClassification;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.test.fixtures.Shop;
import io.github.gkosharovdev.sagaforge.test.fixtures.Shop.CardDeclined;
import io.github.gkosharovdev.sagaforge.test.fixtures.Shop.GatewayTimeout;
import io.github.gkosharovdev.sagaforge.test.fixtures.Shop.OrderPlaced;
import io.github.gkosharovdev.sagaforge.test.fixtures.Shop.PaymentDesk;
import io.github.gkosharovdev.sagaforge.test.fixtures.Shop.PaymentTaken;
import io.github.gkosharovdev.sagaforge.test.fixtures.Shop.RefundPayment;
import io.github.gkosharovdev.sagaforge.test.fixtures.Shop.ReserveStock;
import io.github.gkosharovdev.sagaforge.test.fixtures.Shop.ShopSaga;
import io.github.gkosharovdev.sagaforge.test.fixtures.Shop.StockMissing;
import io.github.gkosharovdev.sagaforge.test.fixtures.Shop.TakePayment;

/** User Story 2: failure, retry and compensation (specs/009-saga-test-fixture tasks.md T028). */
class FailureAndCompensationTest {

    @Test
    @DisplayName("scenario 1: a business failure runs the compensations with their commands")
    void businessFailure() {
        SagaFixture.of(ShopSaga.class).build()
                .given(new OrderPlaced("o-2", "10"), new PaymentTaken("payment-2", "o-2", "10"))
                .when(new StockMissing("o-2"))
                .expectOutcome(HandlingOutcome.COMPENSATED)
                .expectCompletedStep(Shop.MISSING)
                .expectCompensations(Shop.REFUND)
                .expectCommands(new RefundPayment("o-2", "payment-2", "10"))
                .expectCompensatedAfter(Shop.MISSING)
                .expectStatus(SagaStatus.COMPENSATED);
    }

    @Test
    @DisplayName("scenario 2 and SC-005: two transient failures, then success, without waiting 30 s")
    void transientThenSuccess() {
        PaymentDesk desk = new PaymentDesk();
        SagaFixture fixture = SagaFixture.of(ShopSaga.class).handlers(desk)
                .failCommand(TakePayment.class, GatewayTimeout::new, 2).build();
        long started = System.nanoTime();

        Then then = fixture.when(new OrderPlaced("o-3", "10"))
                .expectAttempts(Shop.PLACED, 3)
                .expectCompletedStep(Shop.PAID)
                .expectCommands(new TakePayment("o-3", "10"), new ReserveStock("o-3"))
                .expectStatus(SagaStatus.RUNNING);

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
        assertThat(fixture.clock().instant()).isEqualTo(FixtureClock.DEFAULT_START.plusSeconds(20));
        assertThat(then.result().allAttempts()).filteredOn(command -> command.command() instanceof TakePayment)
                .extracting(RecordedCommand::attempt).containsExactly(1, 2, 3);
        assertThat(then.result().allAttempts()).filteredOn(command -> command.command() instanceof TakePayment)
                .extracting(RecordedCommand::commandId).containsOnly(desk.payments().get(0));
        assertThat(desk.payments()).hasSize(1);
    }

    @Test
    @DisplayName("scenario 3 and SC-005: a transient failure beyond the policy is exhausted, without waiting 30 s")
    void exhausted() {
        long started = System.nanoTime();
        SagaFixture fixture = SagaFixture.of(ShopSaga.class).failCommand(ReserveStock.class, GatewayTimeout::new)
                .build();
        fixture.given(new OrderPlaced("o-4", "10"));

        fixture.when(new PaymentTaken("payment-4", "o-4", "10"))
                .expectOutcome(HandlingOutcome.COMPENSATED)
                .expectFailure(GatewayTimeout.class, FailureClassification.EXHAUSTED)
                .expectAttempts(Shop.PAID, 1)
                .expectCompensations()
                .expectCompensatedAfter(Shop.PAID);

        SagaFixture slow = SagaFixture.of(ShopSaga.class).failCommand(TakePayment.class, GatewayTimeout::new).build();
        slow.when(new OrderPlaced("o-5", "10"))
                .expectAttempts(Shop.PLACED, 4)
                .expectFailure(GatewayTimeout.class, FailureClassification.EXHAUSTED)
                .expectStatus(SagaStatus.FAILED);
        assertThat(slow.clock().instant()).isEqualTo(FixtureClock.DEFAULT_START.plusSeconds(30));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("scenario 3: exhausted retries after a committed step compensate it")
    void exhaustedCompensates() {
        SagaFixture fixture = SagaFixture.of(ShopSaga.class).build();
        fixture.given(new OrderPlaced("o-6", "10"), new PaymentTaken("payment-6", "o-6", "10"));

        fixture.when(new StockMissing("o-6")).expectStatus(SagaStatus.COMPENSATED);
        SagaFixture failing = SagaFixture.of(ShopSaga.class).failCommand(ReserveStock.class, CardDeclined::new)
                .build();
        failing.given(new OrderPlaced("o-7", "10"));

        failing.when(new PaymentTaken("payment-7", "o-7", "10"))
                .expectStatus(SagaStatus.COMPENSATED)
                .expectFailure(CardDeclined.class, FailureClassification.FINAL)
                .expectCompensations()
                .expectCompensatedAfter(Shop.PAID);
    }

    @Test
    @DisplayName("scenario 4: an unclassified failure ends FAILED and compensates nothing")
    void unclassified() {
        SagaFixture.of(ShopSaga.class).build()
                .given(new OrderPlaced("o-8", "10"))
                .when(new PaymentTaken("payment-8", "o-8", "99"))
                .expectOutcome(HandlingOutcome.FAILED)
                .expectFailure(IllegalStateException.class, FailureClassification.UNCLASSIFIED)
                .expectNoCommands()
                .expectCompensations()
                .expectStatus(SagaStatus.FAILED);
    }

    @Test
    @DisplayName("scenario 5: a failing compensation requires intervention")
    void failingCompensation() {
        SagaFixture.of(ShopSaga.class)
                .failCommand(RefundPayment.class, refund -> refund.amount().equals("10"),
                        () -> new IllegalStateException("refund refused"))
                .build()
                .given(new OrderPlaced("o-9", "10"), new PaymentTaken("payment-9", "o-9", "10"))
                .when(new StockMissing("o-9"))
                .expectOutcome(HandlingOutcome.REQUIRES_INTERVENTION)
                .expectStatus(SagaStatus.REQUIRES_INTERVENTION)
                .expectCompensations(Shop.REFUND + " (failed)")
                .expectFailure(IllegalStateException.class, FailureClassification.UNCLASSIFIED)
                .expectNoCommands();
    }

    @Test
    @DisplayName("scenario 6: a rule by content applies only to matching commands, a counted rule only n times")
    void rulesByContentAndCount() {
        SagaFixture fixture = SagaFixture.of(ShopSaga.class)
                .failCommand(TakePayment.class, payment -> payment.orderId().equals("o-bad"), CardDeclined::new)
                .failCommand(ReserveStock.class, () -> new IllegalStateException("once"), 1)
                .build();

        fixture.when(new OrderPlaced("o-bad", "10")).expectStatus(SagaStatus.FAILED);
        fixture.when(new OrderPlaced("o-ok", "10")).expectStatus(SagaStatus.RUNNING);
        fixture.when(new PaymentTaken("p", "o-ok", "10")).expectStatus(SagaStatus.FAILED);
        fixture.when(new OrderPlaced("o-ok2", "10"));
        fixture.when(new PaymentTaken("p2", "o-ok2", "10")).expectStatus(SagaStatus.RUNNING);
    }

    @Test
    @DisplayName("context values written by a failed attempt stay visible, as at runtime")
    void valuesOfFailedAttemptsStay() {
        SagaFixture.of(ShopSaga.class).failCommand(TakePayment.class, () -> new IllegalStateException("down"))
                .build()
                .when(new OrderPlaced("o-10", "10"))
                .expectStatus(SagaStatus.FAILED)
                .expectContext("amount", "10");
    }
}
