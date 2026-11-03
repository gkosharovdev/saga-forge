package io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking;

import java.util.LinkedHashMap;
import java.util.Map;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateLoader;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateSaver;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.Snapshottable;

/**
 * Client repository for {@link Booking}: a plain class with an {@code @AggregateSaver} (used after the
 * creation handler) and, for completeness, an {@code @AggregateLoader}, backed by its own map. {@link Booking}
 * is immutable, so a snapshot copies the map only. {@link #saveCount()} counts {@code save} calls and is not
 * part of the snapshot.
 */
public class BookingRepository implements Snapshottable {

    private final Map<String, Booking> bookings = new LinkedHashMap<>();
    private int saveCount;

    /**
     * Loads a booking by id.
     *
     * @return the stored booking, or {@code null} if none exists; the gateway reports {@code null} as
     *         {@code AggregateNotFoundException}
     */
    @AggregateLoader
    public Booking load(String bookingId) {
        return bookings.get(bookingId);
    }

    /** Stores the booking under its id and counts the call. */
    @AggregateSaver
    public void save(Booking booking) {
        if (booking == null) {
            throw new IllegalArgumentException("booking must not be null");
        }
        bookings.put(booking.bookingId(), booking);
        saveCount++;
    }

    /** Number of {@link #save(Booking)} calls since construction or {@link #reset()}. */
    public int saveCount() {
        return saveCount;
    }

    /** Clears all bookings and the save count. */
    public void reset() {
        bookings.clear();
        saveCount = 0;
    }

    @Override
    public Object snapshot() {
        return new State(Map.copyOf(bookings));
    }

    @Override
    public void restore(Object snapshot) {
        if (!(snapshot instanceof State state)) {
            throw new IllegalArgumentException("Not a BookingRepository snapshot: " + snapshot);
        }
        bookings.clear();
        bookings.putAll(state.bookings());
    }

    private record State(Map<String, Booking> bookings) {
    }
}
