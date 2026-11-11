package io.github.gkosharovdev.sagaforge.spring.modulith.app.orders;

import java.math.BigDecimal;

import io.github.gkosharovdev.sagaforge.core.annotation.Aggregate;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;

/** An order was placed; a negative amount makes the saga's payment step fail. */
@DomainEvent(aggregate = OrderPlaced.Order.class)
public record OrderPlaced(@AggregateIdentifier String orderId, BigDecimal amount) {

    @Aggregate
    public static class Order {

        @AggregateIdentifier
        private String orderId;
    }
}
