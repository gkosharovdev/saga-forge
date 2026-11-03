package io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;
import io.github.gkosharovdev.sagaforge.core.event.DomainEvents;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.Snapshottable;

/**
 * Service-hosted handler for {@link InitiatePayment}: creates a {@link Payment} and records
 * {@link PaymentInitiatedEvent}. A plain class with no Saga Forge supertype; {@link Snapshottable} is a
 * test-only fixture contract. The provider's answer ({@link PaymentConfirmedEvent} /
 * {@link PaymentDeclinedEvent}) is not produced here; tests deliver it through the transport.
 */
public class PaymentService implements Snapshottable {

    private final Map<String, Payment> payments = new LinkedHashMap<>();
    private final FaultSwitch faults = new FaultSwitch();

    /** The {@link Payment} id this service uses for a booking. */
    public static String paymentIdFor(String bookingId) {
        return "payment-" + bookingId;
    }

    /** Creates the payment, then throws if a failure is armed, otherwise records {@link PaymentInitiatedEvent}. */
    @CommandHandler
    public void handle(InitiatePayment command) {
        String paymentId = paymentIdFor(command.bookingId());
        payments.put(paymentId, new Payment(paymentId, command.bookingId(), command.amount()));
        faults.throwIfArmed();
        DomainEvents.record(new PaymentInitiatedEvent(paymentId, command.bookingId(), command.amount()));
    }

    /** Arms a one-shot failure with a default {@link IllegalStateException}. */
    public void failNext() {
        failNext(new IllegalStateException("PaymentService failure injected"));
    }

    /** Arms a one-shot failure: the next {@code handle} call throws {@code failure} after its change. */
    public void failNext(RuntimeException failure) {
        faults.arm(failure);
    }

    /** The payment with the given id, if initiated. */
    public Optional<Payment> find(String paymentId) {
        return Optional.ofNullable(payments.get(paymentId));
    }

    /** Ids of the payments changed so far, in change order. */
    public Set<String> changedAggregates() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(payments.keySet()));
    }

    /** Clears all payments and disarms any pending failure. */
    public void reset() {
        payments.clear();
        faults.disarm();
    }

    @Override
    public Object snapshot() {
        return new State(List.copyOf(payments.values()));
    }

    @Override
    public void restore(Object snapshot) {
        if (!(snapshot instanceof State state)) {
            throw new IllegalArgumentException("Not a PaymentService snapshot: " + snapshot);
        }
        payments.clear();
        state.payments().forEach(payment -> payments.put(payment.paymentId(), payment));
    }

    private record State(List<Payment> payments) {
    }
}
