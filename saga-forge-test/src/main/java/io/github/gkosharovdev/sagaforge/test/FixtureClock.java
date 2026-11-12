package io.github.gkosharovdev.sagaforge.test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The time a {@link SagaFixture}'s runtime observes: fixed until a test, or a retry wait, moves it
 * ({@code specs/009-saga-test-fixture} FR-009). Retry backoff advances it by the wait instead of sleeping, so a test
 * of a policy with 30 seconds of backoff runs in milliseconds.
 *
 * <p>Thread-safe. Always in UTC.</p>
 */
public final class FixtureClock extends Clock {

    /** Where a fixture's clock starts unless {@code SagaFixture.Builder.startingAt} says otherwise. */
    public static final Instant DEFAULT_START = Instant.parse("2026-01-01T00:00:00Z");

    private final AtomicReference<Instant> now;

    FixtureClock(Instant start) {
        if (start == null) {
            throw new IllegalArgumentException("start must not be null");
        }
        this.now = new AtomicReference<>(start);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    /**
     * @return this clock for UTC; otherwise a clock fixed at the current instant in {@code zone}
     */
    @Override
    public Clock withZone(ZoneId zone) {
        return ZoneOffset.UTC.equals(zone) ? this : Clock.fixed(instant(), zone);
    }

    @Override
    public Instant instant() {
        return now.get();
    }

    /**
     * Sets the current time.
     *
     * @throws IllegalArgumentException if {@code instant} is {@code null}
     */
    public void set(Instant instant) {
        if (instant == null) {
            throw new IllegalArgumentException("instant must not be null");
        }
        now.set(instant);
    }

    /**
     * Moves the current time forward.
     *
     * @throws IllegalArgumentException if {@code duration} is {@code null} or negative
     */
    public void advance(Duration duration) {
        if (duration == null || duration.isNegative()) {
            throw new IllegalArgumentException("duration must be zero or positive, but is " + duration);
        }
        now.updateAndGet(instant -> instant.plus(duration));
    }
}
