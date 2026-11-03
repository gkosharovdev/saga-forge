package io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;
import io.github.gkosharovdev.sagaforge.core.event.DomainEvents;

/**
 * Service-hosted handler for {@link ReserveItems}: changes a {@link Reservation} and records
 * {@link ItemsReserved}. A plain class with no Saga Forge supertype; {@link Snapshottable} is a test-only
 * fixture contract.
 */
public class ReservationService implements Snapshottable {

    private final Map<String, Reservation> reservations = new LinkedHashMap<>();
    private final FaultSwitch faults = new FaultSwitch();

    /** The {@link Reservation} id this service uses for an order. */
    public static String reservationIdFor(String orderId) {
        return "reservation-" + orderId;
    }

    /** Applies the reservation, then throws if a failure is armed, otherwise records {@link ItemsReserved}. */
    @CommandHandler
    public void handle(ReserveItems command) {
        String reservationId = reservationIdFor(command.orderId());
        reservations.put(reservationId, new Reservation(reservationId));
        faults.throwIfArmed();
        DomainEvents.record(new ItemsReserved(reservationId, command.orderId(), command.quantity()));
    }

    /** Arms a one-shot failure with a default {@link IllegalStateException}. */
    public void failNext() {
        failNext(new IllegalStateException("ReservationService failure injected"));
    }

    /** Arms a one-shot failure: the next {@code handle} call throws {@code failure} after its change. */
    public void failNext(RuntimeException failure) {
        faults.arm(failure);
    }

    /** Ids of the reservations changed so far, in change order. */
    public Set<String> changedAggregates() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(reservations.keySet()));
    }

    /** Clears all reservations and disarms any pending failure. */
    public void reset() {
        reservations.clear();
        faults.disarm();
    }

    @Override
    public Object snapshot() {
        return new State(List.copyOf(reservations.values()));
    }

    @Override
    public void restore(Object snapshot) {
        if (!(snapshot instanceof State state)) {
            throw new IllegalArgumentException("Not a ReservationService snapshot: " + snapshot);
        }
        reservations.clear();
        state.reservations().forEach(reservation -> reservations.put(reservation.id(), reservation));
    }

    private record State(List<Reservation> reservations) {
    }
}
