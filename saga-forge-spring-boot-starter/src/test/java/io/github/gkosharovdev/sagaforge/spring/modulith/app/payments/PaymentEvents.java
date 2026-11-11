package io.github.gkosharovdev.sagaforge.spring.modulith.app.payments;

import java.math.BigDecimal;

import io.github.gkosharovdev.sagaforge.core.annotation.Aggregate;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.Command;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;

/** The payments module's API: its command, its event and the aggregate the event belongs to. */
public final class PaymentEvents {

    private PaymentEvents() {
    }

    @Command
    public record TakePayment(String orderId, BigDecimal amount) {
    }

    @DomainEvent(aggregate = Payment.class)
    public record PaymentTaken(@AggregateIdentifier String paymentId, String orderId, BigDecimal amount) {
    }

    @Aggregate
    public static class Payment {

        @AggregateIdentifier
        private String paymentId;
    }
}
