package io.github.gkosharovdev.sagaforge.example.checkout;

import io.github.gkosharovdev.sagaforge.core.compensation.CompensationTriggerKind;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.github.gkosharovdev.sagaforge.core.outcome.SagaCompensated;
import io.github.gkosharovdev.sagaforge.core.outcome.SagaCompleted;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.example.checkout.commands.AuthorizePaymentCommand;
import io.github.gkosharovdev.sagaforge.example.checkout.commands.ConfirmOrderCommand;
import io.github.gkosharovdev.sagaforge.example.checkout.commands.RejectOrderCommand;
import io.github.gkosharovdev.sagaforge.example.checkout.commands.ReleaseStockCommand;
import io.github.gkosharovdev.sagaforge.example.checkout.commands.ReserveStockCommand;
import io.github.gkosharovdev.sagaforge.example.checkout.events.OrderPlacedEvent;
import io.github.gkosharovdev.sagaforge.example.checkout.events.PaymentAuthorizedEvent;
import io.github.gkosharovdev.sagaforge.example.checkout.events.PaymentDeclinedEvent;
import io.github.gkosharovdev.sagaforge.example.checkout.events.StockReservedEvent;
import io.github.gkosharovdev.sagaforge.example.checkout.events.StockUnavailableEvent;
import io.github.gkosharovdev.sagaforge.test.SagaFixture;

/**
 * {@link CheckoutSaga} in record-and-answer mode: each test answers the saga's commands with the event Inventory or
 * Payments would send, and checks what the saga does next. The outbox command's handler class is registered, as the
 * runtime refuses to store an outbox command nobody handles.
 */
@Timeout(value = 1, unit = TimeUnit.SECONDS)
class CheckoutSagaFixtureTest {

    private static final BigDecimal AMOUNT = new BigDecimal("59.90");
    private static final List<OrderLine> LINES = List.of(new OrderLine("sku-mug", 2));
    private static final OrderPlacedEvent PLACED = new OrderPlacedEvent("o-1", LINES, AMOUNT, "1 Main Street",
            false);
    private static final StockReservedEvent RESERVED = new StockReservedEvent("reservation-o-1", "o-1");

    private final SagaFixture fixture = SagaFixture.of(CheckoutSaga.class).register(InventoryService.class).build();

    @Test
    @DisplayName("a placed order asks Inventory to hold its items, then waits")
    void placedOrderReservesStock() {
        fixture.when(PLACED)
                .expectStarted()
                .expectCommands(new ReserveStockCommand("o-1", LINES))
                .expectStatus(SagaStatus.RUNNING);
    }

    @Test
    @DisplayName("held stock authorises the payment")
    void reservedStockAuthorisesPayment() {
        fixture.given(PLACED)
                .when(new StockReservedEvent("reservation-o-1", "o-1"))
                .expectCommands(new AuthorizePaymentCommand("o-1", AMOUNT))
                .expectContext("reservationId", "reservation-o-1");
    }

    @Test
    @DisplayName("an authorised payment confirms the order and ends the checkout")
    void authorisedPaymentConfirmsOrder() {
        fixture.given(PLACED, new StockReservedEvent("reservation-o-1", "o-1"))
                .when(new PaymentAuthorizedEvent("payment-1", "o-1", AMOUNT))
                .expectCommands(new ConfirmOrderCommand("o-1", "reservation-o-1", "payment-1"))
                .expectEndedWith("onPaymentAuthorized")
                .expectOutcomeEvent(SagaCompleted.class);
    }

    @Test
    @DisplayName("out of stock: the order is rejected, and the release is sent anyway, for Inventory to ignore")
    void outOfStockRejectsOrder() {
        fixture.given(PLACED)
                .when(new StockUnavailableEvent("reservation-o-1", "o-1", "sku-mug"))
                .expectCompensations("cancelCheckout")
                // The release is an outbox command, reported when it is delivered, after the in-process rejection.
                .expectCommands(new RejectOrderCommand("o-1", Order.RejectionReason.OUT_OF_STOCK),
                        new ReleaseStockCommand("o-1"))
                .expectCompensatedAfter("onStockUnavailable");
    }

    @Test
    @DisplayName("declined card: the held stock is released and the order rejected")
    void declinedPaymentReleasesStock() {
        fixture.given(PLACED, new StockReservedEvent("reservation-o-1", "o-1"))
                .when(new PaymentDeclinedEvent("declined-1", "o-1", "insufficient funds"))
                .expectCompensations("cancelCheckout")
                .expectCommands(new RejectOrderCommand("o-1", Order.RejectionReason.PAYMENT_NOT_AUTHORIZED),
                        new ReleaseStockCommand("o-1"))
                .expectCompensatedAfter("onPaymentDeclined")
                .expectOutcomeEvent(SagaCompensated.class);
    }

    @Test
    @DisplayName("provider timeouts: the authorisation is retried with one idempotency key and authorised once")
    void timeoutsAreRetried() {
        PaymentProvider provider = new PaymentProvider();
        provider.timeOutNext(2);
        SagaFixture withPayments = SagaFixture.of(CheckoutSaga.class).register(InventoryService.class)
                .handlers(new PaymentService(provider)).build();

        withPayments.given(PLACED)
                .when(new StockReservedEvent("reservation-o-1", "o-1"))
                .expectAttempts("onStockReserved", 3)
                .expectEndedWith("onPaymentAuthorized");
        assertThat(provider.authorizations()).hasSize(1);
    }

    @Test
    @DisplayName("retries run out: onStockReserved never completes, and the stock Inventory holds is still released")
    void exhaustedRetriesReleaseStock() {
        PaymentProvider provider = new PaymentProvider();
        provider.timeOutNext(10);
        SagaFixture withPayments = SagaFixture.of(CheckoutSaga.class).register(InventoryService.class)
                .handlers(new PaymentService(provider)).build();

        withPayments.given(PLACED)
                .when(new StockReservedEvent("reservation-o-1", "o-1"))
                .expectAttempts("onStockReserved", 3)
                .expectCompensations("cancelCheckout")
                .expectCommand(ReleaseStockCommand.class, release -> release.orderId().equals("o-1"))
                .expectStatus(SagaStatus.COMPENSATED);
    }

    @Test
    @DisplayName("held stock opens the payment window: 30 minutes, or 10 at express checkout")
    void stockOpensThePaymentWindow() {
        fixture.given(PLACED)
                .when(RESERVED)
                .expectOpenDeadline("paymentWindow");
        assertThat(fixture.instance(CheckoutSaga.class, "o-1").openDeadlines().get("paymentWindow").dueAt())
                .isEqualTo(fixture.clock().instant().plus(Duration.ofMinutes(30)));

        fixture.given(new OrderPlacedEvent("o-2", LINES, AMOUNT, "1 Main Street", true))
                .when(new StockReservedEvent("reservation-o-2", "o-2"));
        assertThat(fixture.instance(CheckoutSaga.class, "o-2").openDeadlines().get("paymentWindow").dueAt())
                .isEqualTo(fixture.clock().instant().plus(Duration.ofMinutes(10)));
    }

    @Test
    @DisplayName("an abandoned card challenge: the window closes, the stock is released and the order rejected")
    void abandonedChallengeTimesOut() {
        fixture.given(PLACED, RESERVED)
                .whenTimeElapses(Duration.ofMinutes(29))
                .expectNoCommands();

        fixture.whenTimeElapses(Duration.ofMinutes(1))
                .expectDeadlineExpired("paymentWindow")
                .expectCompensations("cancelCheckout")
                .expectCommands(new RejectOrderCommand("o-1", Order.RejectionReason.PAYMENT_TIMED_OUT),
                        new ReleaseStockCommand("o-1"))
                .expectCompensatedAfter("onPaymentWindowExpired")
                .expectStatus(SagaStatus.COMPENSATED)
                .expectOutcomeEvent(SagaCompensated.class, compensated ->
                        compensated.trigger().kind() == CompensationTriggerKind.DEADLINE_EXPIRED
                                && "paymentWindow".equals(compensated.trigger().deadlineName()));
    }

    @Test
    @DisplayName("payment in time closes the window: an hour later nothing happens")
    void paymentInTimeNeverTimesOut() {
        fixture.given(PLACED, RESERVED)
                .when(new PaymentAuthorizedEvent("payment-1", "o-1", AMOUNT))
                .expectNoOpenDeadline("paymentWindow")
                .expectEndedWith("onPaymentAuthorized");

        fixture.whenTimeElapses(Duration.ofHours(1)).expectNoCommands();
    }

    @Test
    @DisplayName("a declined card closes the window with the rest of the checkout")
    void declineClosesTheWindow() {
        fixture.given(PLACED, RESERVED)
                .when(new PaymentDeclinedEvent("declined-1", "o-1", "insufficient funds"))
                .expectNoOpenDeadline("paymentWindow")
                .expectCompensatedAfter("onPaymentDeclined");

        fixture.whenTimeElapses(Duration.ofHours(1)).expectNoCommands();
    }
}
