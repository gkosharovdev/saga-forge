package io.github.gkosharovdev.sagaforge.spring.fixtures.jdbc;

import java.math.BigDecimal;

import io.github.gkosharovdev.sagaforge.core.annotation.Aggregate;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.Command;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;

/** Commands and events of the Spring Data variant of the order saga. */
public final class JdbcOrderEvents {

    private JdbcOrderEvents() {
    }

    @DomainEvent(aggregate = JdbcOrder.class)
    public record OrderPlaced(@AggregateIdentifier String orderId, BigDecimal amount) {
    }

    @DomainEvent(aggregate = Charge.class)
    public record OrderCharged(@AggregateIdentifier String chargeId, String orderId, String provider) {
    }

    @DomainEvent(aggregate = JdbcOrder.class)
    public record OrderConfirmed(@AggregateIdentifier String orderId) {
    }

    @Command
    public record ChargeOrder(String orderId, BigDecimal amount) {
    }

    @Command
    public record ConfirmOrder(@AggregateIdentifier String orderId) {
    }

    /** The charge {@code OrderCharged} belongs to. */
    @Aggregate
    public static class Charge {

        @AggregateIdentifier
        private String chargeId;
    }
}
