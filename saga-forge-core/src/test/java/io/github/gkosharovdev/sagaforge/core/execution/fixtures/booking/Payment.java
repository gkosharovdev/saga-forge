package io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking;

import io.github.gkosharovdev.sagaforge.core.annotation.Aggregate;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;

/**
 * Marker aggregate changed by {@link PaymentService} and named by the payment events. It declares no
 * {@code @CommandHandler}; the service touches it. Immutable.
 */
@Aggregate
public final class Payment {

    @AggregateIdentifier
    private final String paymentId;

    private final String bookingId;
    private final long amount;

    public Payment(String paymentId, String bookingId, long amount) {
        this.paymentId = paymentId;
        this.bookingId = bookingId;
        this.amount = amount;
    }

    public String paymentId() {
        return paymentId;
    }

    public String bookingId() {
        return bookingId;
    }

    public long amount() {
        return amount;
    }
}
