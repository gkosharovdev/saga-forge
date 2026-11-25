package io.github.gkosharovdev.sagaforge.example.checkout;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.github.gkosharovdev.sagaforge.core.scanner.ReflectiveAnnotationScanner;
import io.github.gkosharovdev.sagaforge.core.scanner.ValidationReport;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.example.checkout.events.DeliveryImpossibleEvent;
import io.github.gkosharovdev.sagaforge.example.checkout.events.ShipmentDeliveredEvent;
import io.github.gkosharovdev.sagaforge.example.checkout.events.ShipmentDispatchedEvent;
import io.github.gkosharovdev.sagaforge.test.SagaFixture;

/**
 * Both sagas with the real handlers: the checkout's last command records {@code OrderConfirmedEvent}, which starts the
 * fulfilment; Logistics' answers are published by the test.
 */
@Timeout(value = 1, unit = TimeUnit.SECONDS)
class CheckoutAndFulfilmentTest {

    private static final BigDecimal AMOUNT = new BigDecimal("59.90");

    private final Orders orders = new Orders();
    private final InventoryService inventory = new InventoryService().stock("sku-mug", 5);
    private final PaymentProvider provider = new PaymentProvider();
    private final LogisticsService logistics = new LogisticsService();
    private SagaFixture fixture;
    private Order order;

    @BeforeEach
    void setUp() {
        fixture = SagaFixture.of(CheckoutSaga.class, FulfilmentSaga.class, Order.class, StockReservation.class,
                        Payment.class, Shipment.class)
                .handlers(orders, inventory, new PaymentService(provider), logistics)
                .build();
        order = new Order("o-1", List.of(new OrderLine("sku-mug", 2)), AMOUNT, "1 Main Street");
        orders.save(order);
    }

    @Test
    @DisplayName("the whole model passes validateAll")
    void modelIsValid() {
        ValidationReport report = new ReflectiveAnnotationScanner().validateAll(List.of(CheckoutSaga.class,
                FulfilmentSaga.class, Order.class, Orders.class, StockReservation.class, Payment.class,
                Shipment.class, InventoryService.class, PaymentService.class, LogisticsService.class));

        assertThat(report.isValid()).as("validateAll errors: %s", report.allErrors()).isTrue();
    }

    @Test
    @DisplayName("checkout confirms the order and hands it to fulfilment, which ends at delivery")
    void happyPath() {
        fixture.when(order.placed());

        assertThat(fixture.instance(CheckoutSaga.class, "o-1").status()).isEqualTo(SagaStatus.COMPLETED);
        assertThat(fixture.instance(FulfilmentSaga.class, "o-1").status()).isEqualTo(SagaStatus.RUNNING);
        assertThat(logistics.bookings()).hasSize(1);

        fixture.when(new ShipmentDispatchedEvent("shipment-1", "o-1"));
        String paymentId = provider.authorizations().keySet().iterator().next();
        assertThat(provider.state(paymentId)).isEqualTo(PaymentProvider.State.CAPTURED);
        assertThat(inventory.reservation("reservation-o-1")).isEqualTo(InventoryService.State.COMMITTED);

        fixture.when(new ShipmentDeliveredEvent("shipment-1", "o-1"))
                .expectEndedWith("onShipmentDelivered");
    }

    @Test
    @DisplayName("impossible delivery after dispatch refunds the captured payment")
    void refundAfterDispatch() {
        fixture.when(order.placed());
        fixture.when(new ShipmentDispatchedEvent("shipment-1", "o-1"));

        fixture.when(new DeliveryImpossibleEvent("shipment-1", "o-1", "returned to sender"))
                .expectCompensatedAfter("onDeliveryImpossible");

        String paymentId = provider.authorizations().keySet().iterator().next();
        assertThat(provider.state(paymentId)).isEqualTo(PaymentProvider.State.REFUNDED);
        assertThat(inventory.reservation("reservation-o-1")).isEqualTo(InventoryService.State.RELEASED);
        assertThat(order.status()).isEqualTo(Order.Status.CANCELLED);
    }

    @Test
    @DisplayName("impossible delivery before dispatch voids the authorisation instead")
    void voidBeforeDispatch() {
        fixture.when(order.placed());

        fixture.when(new DeliveryImpossibleEvent("shipment-1", "o-1", "no courier serves the address"))
                .expectCompensatedAfter("onDeliveryImpossible");

        String paymentId = provider.authorizations().keySet().iterator().next();
        assertThat(provider.state(paymentId)).isEqualTo(PaymentProvider.State.VOIDED);
        assertThat(inventory.available("sku-mug")).isEqualTo(5);
        assertThat(order.status()).isEqualTo(Order.Status.CANCELLED);
    }

    @Test
    @DisplayName("out of stock: the checkout rejects the order and no fulfilment starts")
    void outOfStock() {
        Order big = new Order("o-2", List.of(new OrderLine("sku-mug", 9)), AMOUNT, "1 Main Street");
        orders.save(big);

        fixture.when(big.placed());

        assertThat(fixture.instance(CheckoutSaga.class, "o-2").status()).isEqualTo(SagaStatus.COMPENSATED);
        assertThat(big.status()).isEqualTo(Order.Status.REJECTED);
        assertThat(provider.authorizations()).isEmpty();
    }

    @Test
    @DisplayName("no courier in two hours: fulfilment voids the authorisation, releases the stock and cancels the order")
    void courierDeadline() {
        fixture.when(order.placed());

        fixture.whenTimeElapses(java.time.Duration.ofHours(2))
                .instance(FulfilmentSaga.class, "o-1")
                .expectDeadlineExpired("courierAssignment")
                .expectCompensatedAfter("onCourierNotAssigned");

        String paymentId = provider.authorizations().keySet().iterator().next();
        assertThat(provider.state(paymentId)).isEqualTo(PaymentProvider.State.VOIDED);
        assertThat(inventory.available("sku-mug")).isEqualTo(5);
        assertThat(order.status()).isEqualTo(Order.Status.CANCELLED);
    }
}
