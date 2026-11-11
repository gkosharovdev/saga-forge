package io.github.gkosharovdev.sagaforge.spring.fixtures.order;

import java.math.BigDecimal;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.Command;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;

/** The canonical example's commands and events (saga-forge-example), copied for the starter's tests. */
public final class OrderEvents {

    private OrderEvents() {
    }

    @DomainEvent(aggregate = Order.class)
    public record OrderCreatedEvent(@AggregateIdentifier String orderId, BigDecimal amount) {
    }

    @DomainEvent(aggregate = Payment.class)
    public record PaymentProcessedEvent(@AggregateIdentifier String paymentId, String orderId, BigDecimal amount) {
    }

    @DomainEvent(aggregate = Order.class)
    public record OrderCompletedEvent(@AggregateIdentifier String orderId) {
    }

    /** Recorded with {@link OrderCompletedEvent}; no step of the order saga takes it, so it travels the transport. */
    @DomainEvent(aggregate = Order.class)
    public record OrderReadyToShipEvent(@AggregateIdentifier String orderId) {
    }

    @DomainEvent(aggregate = Order.class)
    public record StockUnavailableEvent(@AggregateIdentifier String orderId) {
    }

    @Command
    public record ProcessPaymentCommand(String orderId, BigDecimal amount) {
    }

    @Command
    public record RefundPaymentCommand(String orderId, String paymentId, BigDecimal amount) {
    }

    @Command
    public record ReserveStockCommand(@AggregateIdentifier String orderId) {
    }
}
