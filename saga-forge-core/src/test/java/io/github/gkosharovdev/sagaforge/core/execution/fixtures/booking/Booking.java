package io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking;

import io.github.gkosharovdev.sagaforge.core.annotation.Aggregate;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;
import io.github.gkosharovdev.sagaforge.core.event.DomainEvents;

/**
 * {@code @Aggregate} created by a constructor {@code @CommandHandler} (spec US2 scenario 10). It extends and
 * implements no Saga Forge type and is immutable once created. The gateway invokes the constructor directly and
 * saves the new instance through {@link BookingRepository#save(Booking)}.
 *
 * <p>Fault injection: there is no instance to arm before a constructor runs, so the switch is static and
 * thread-local. {@link #failNext()} / {@link #failNext(RuntimeException)} arm a one-shot failure on the calling
 * thread: the next creation sets its fields, then throws, before recording its event (so nothing is saved).
 * Call {@link #resetFaults()} before each test.</p>
 */
@Aggregate
public final class Booking {

    private static final ThreadLocal<FaultSwitch> FAULTS = ThreadLocal.withInitial(FaultSwitch::new);

    @AggregateIdentifier
    private final String bookingId;

    private final String claimId;
    private final String paymentId;

    /** Creation handler: creates the booking, then throws if armed, otherwise records {@link BookingConfirmedEvent}. */
    @CommandHandler
    public Booking(ConfirmBooking command) {
        this.bookingId = command.bookingId();
        this.claimId = command.claimId();
        this.paymentId = command.paymentId();
        FAULTS.get().throwIfArmed();
        DomainEvents.record(new BookingConfirmedEvent(bookingId, claimId, paymentId));
    }

    public String bookingId() {
        return bookingId;
    }

    public String claimId() {
        return claimId;
    }

    public String paymentId() {
        return paymentId;
    }

    /** Arms a one-shot creation failure on the calling thread with a default {@link IllegalStateException}. */
    public static void failNext() {
        failNext(new IllegalStateException("Booking creation failure injected"));
    }

    /** Arms a one-shot creation failure on the calling thread: the next constructor call throws {@code failure}. */
    public static void failNext(RuntimeException failure) {
        FAULTS.get().arm(failure);
    }

    /** Disarms any pending creation failure on the calling thread. */
    public static void resetFaults() {
        FAULTS.get().disarm();
    }
}
