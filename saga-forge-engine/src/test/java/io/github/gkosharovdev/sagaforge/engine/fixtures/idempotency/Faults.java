package io.github.gkosharovdev.sagaforge.engine.fixtures.idempotency;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/** Named fault points that throw a configured exception a configured number of times. */
public final class Faults {

    /** Reached at the end of {@link ChargeSaga#onOrderPlaced}, after its commands were handled. */
    public static final String AFTER_PLACED = "afterPlaced";

    /** Reached at the end of {@link ChargeSaga#onOrderPacked}, after its commands were handled. */
    public static final String AFTER_PACKED = "afterPacked";

    /** Reached at the end of {@link ChargeSaga#onOrderDelivered}. */
    public static final String AFTER_DELIVERED = "afterDelivered";

    /** Reached in {@link ChargeService#handle} after the provider charged. */
    public static final String IN_CHARGE_HANDLER = "inChargeHandler";

    private record Armed(int times, Supplier<? extends RuntimeException> failure) {
    }

    private final Map<String, Armed> armed = new HashMap<>();
    private final Map<String, Integer> reached = new HashMap<>();

    /** Makes {@code point} throw {@code failure} the next {@code times} times it is reached. */
    public Faults fail(String point, int times, Supplier<? extends RuntimeException> failure) {
        armed.put(point, new Armed(times, failure));
        return this;
    }

    /** How often {@code point} was reached. */
    public int reached(String point) {
        return reached.getOrDefault(point, 0);
    }

    /** Counts {@code point} and throws if it is armed. */
    public void check(String point) {
        reached.merge(point, 1, Integer::sum);
        Armed current = armed.get(point);
        if (current == null || current.times() == 0) {
            return;
        }
        armed.put(point, new Armed(current.times() - 1, current.failure()));
        throw current.failure().get();
    }
}
