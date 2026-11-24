package io.github.gkosharovdev.sagaforge.example.checkout;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.github.gkosharovdev.sagaforge.core.outcome.SagaCompensated;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.example.checkout.commands.CancelOrderCommand;
import io.github.gkosharovdev.sagaforge.example.checkout.commands.CapturePaymentCommand;
import io.github.gkosharovdev.sagaforge.example.checkout.commands.CommitStockCommand;
import io.github.gkosharovdev.sagaforge.example.checkout.commands.ReleasePaymentCommand;
import io.github.gkosharovdev.sagaforge.example.checkout.commands.ReleaseStockCommand;
import io.github.gkosharovdev.sagaforge.example.checkout.commands.RequestShipmentCommand;
import io.github.gkosharovdev.sagaforge.example.checkout.events.DeliveryImpossibleEvent;
import io.github.gkosharovdev.sagaforge.example.checkout.events.OrderConfirmedEvent;
import io.github.gkosharovdev.sagaforge.example.checkout.events.ShipmentDeliveredEvent;
import io.github.gkosharovdev.sagaforge.example.checkout.events.ShipmentDispatchedEvent;
import io.github.gkosharovdev.sagaforge.test.SagaFixture;

/** {@link FulfilmentSaga} in record-and-answer mode: the tests play Logistics. */
@Timeout(value = 1, unit = TimeUnit.SECONDS)
class FulfilmentSagaFixtureTest {

    private static final List<OrderLine> LINES = List.of(new OrderLine("sku-mug", 2));
    private static final OrderConfirmedEvent CONFIRMED =
            new OrderConfirmedEvent("o-1", LINES, "1 Main Street", "reservation-o-1", "payment-1");

    private final SagaFixture fixture = SagaFixture.of(FulfilmentSaga.class)
            .register(LogisticsService.class, PaymentService.class, InventoryService.class).build();

    @Test
    @DisplayName("a confirmed order asks Logistics to deliver it, then waits")
    void confirmedOrderRequestsShipment() {
        fixture.when(CONFIRMED)
                .expectStarted()
                .expectCommands(new RequestShipmentCommand("o-1", LINES, "1 Main Street"))
                .expectStatus(SagaStatus.RUNNING);
    }

    @Test
    @DisplayName("dispatch takes the payment and the stock for good")
    void dispatchCapturesPayment() {
        fixture.given(CONFIRMED)
                .when(new ShipmentDispatchedEvent("shipment-1", "o-1"))
                .expectCommands(new CapturePaymentCommand("o-1", "payment-1"),
                        new CommitStockCommand("o-1", "reservation-o-1"))
                .expectStatus(SagaStatus.RUNNING);
    }

    @Test
    @DisplayName("delivery ends the fulfilment")
    void deliveryEndsFulfilment() {
        fixture.given(CONFIRMED, new ShipmentDispatchedEvent("shipment-1", "o-1"))
                .when(new ShipmentDeliveredEvent("shipment-1", "o-1"))
                .expectNoCommands()
                .expectEndedWith("onShipmentDelivered");
    }

    @Test
    @DisplayName("impossible delivery gives the money and the stock back and cancels the order")
    void impossibleDeliveryCancelsOrder() {
        fixture.given(CONFIRMED, new ShipmentDispatchedEvent("shipment-1", "o-1"))
                .when(new DeliveryImpossibleEvent("shipment-1", "o-1", "address not found"))
                .expectCompensations("cancelOrder")
                .expectCommands(new CancelOrderCommand("o-1", "address not found"),
                        new ReleasePaymentCommand("o-1", "payment-1"),
                        new ReleaseStockCommand("o-1"))
                .expectCompensatedAfter("onDeliveryImpossible")
                .expectOutcomeEvent(SagaCompensated.class);
    }
}
