package io.github.gkosharovdev.sagaforge.runtime.fixtures.concurrency;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Watches {@link SignalSaga}'s steps across threads: how many run at once per {@code sagaId} and the maximum overlap,
 * the order steps entered and exited, what each step read from {@code SagaContext}; it can hold a step on a latch and
 * make every step dwell to widen a race.
 *
 * <p>Test-only. Thread-safe.</p>
 */
public final class StepProbe {

    private static final long HOLD_TIMEOUT_SECONDS = 10;

    /** A step held until {@code release} counts down; {@code entered} counts down when it is reached. */
    private record Hold(String sagaId, String step, CountDownLatch entered, CountDownLatch release) {
    }

    private final Map<String, AtomicInteger> active = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> maxOverlap = new ConcurrentHashMap<>();
    private final List<String> trace = new ArrayList<>();
    private final Map<String, Optional<String>> seen = new ConcurrentHashMap<>();
    private final List<Hold> holds = new ArrayList<>();
    private volatile long dwellMillis;

    /** Every step sleeps {@code millis} while it is running. */
    public StepProbe dwell(long millis) {
        this.dwellMillis = millis;
        return this;
    }

    /**
     * Holds the next run of {@code step} for {@code sagaId} until {@code release} counts down; {@code entered} counts
     * down when the step is reached.
     */
    public StepProbe hold(String sagaId, String step, CountDownLatch entered, CountDownLatch release) {
        synchronized (holds) {
            holds.add(new Hold(sagaId, step, entered, release));
        }
        return this;
    }

    /** The largest number of steps of {@code sagaId} that ran at the same time. */
    public int maxOverlap(String sagaId) {
        AtomicInteger max = maxOverlap.get(sagaId);
        return max == null ? 0 : max.get();
    }

    /** {@code enter:<step>} and {@code exit:<step>} in the order they happened, all instances together. */
    public List<String> trace() {
        synchronized (trace) {
            return List.copyOf(trace);
        }
    }

    /** The steps that entered, in order. */
    public List<String> enteredSteps() {
        return trace().stream().filter(line -> line.startsWith("enter:")).map(line -> line.substring(6)).toList();
    }

    /** What {@code step} read of the other signal's context value; empty if it read nothing. */
    public Optional<String> seenBy(String step) {
        return seen.getOrDefault(step, Optional.empty());
    }

    void enter(String sagaId, String step) {
        int now = active.computeIfAbsent(sagaId, id -> new AtomicInteger()).incrementAndGet();
        maxOverlap.computeIfAbsent(sagaId, id -> new AtomicInteger()).accumulateAndGet(now, Math::max);
        record("enter:" + step);
        awaitHold(sagaId, step);
        sleep(dwellMillis);
    }

    void saw(String step, Optional<String> value) {
        seen.put(step, value);
    }

    void exit(String sagaId, String step) {
        record("exit:" + step);
        active.get(sagaId).decrementAndGet();
    }

    private void record(String line) {
        synchronized (trace) {
            trace.add(line);
        }
    }

    private void awaitHold(String sagaId, String step) {
        Hold hold;
        synchronized (holds) {
            hold = holds.stream().filter(h -> h.sagaId().equals(sagaId) && h.step().equals(step)).findFirst()
                    .orElse(null);
            holds.remove(hold);
        }
        if (hold == null) {
            return;
        }
        hold.entered().countDown();
        try {
            if (!hold.release().await(HOLD_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Step " + step + " of " + sagaId + " was never released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while held in step " + step, e);
        }
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while dwelling in a step", e);
        }
    }
}
