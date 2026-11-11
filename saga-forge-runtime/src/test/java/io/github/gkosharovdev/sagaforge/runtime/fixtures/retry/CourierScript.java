package io.github.gkosharovdev.sagaforge.runtime.fixtures.retry;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Supplier;

/** What {@link CourierService} does on each call: throw the next scripted failure, or succeed once none is left. */
public final class CourierScript {

    private final Deque<Supplier<? extends RuntimeException>> failures = new ArrayDeque<>();
    private int calls;

    /** Makes the next {@code times} calls throw {@code failure}, after the failures already scripted. */
    public CourierScript thenFail(int times, Supplier<? extends RuntimeException> failure) {
        for (int i = 0; i < times; i++) {
            failures.add(failure);
        }
        return this;
    }

    public int calls() {
        return calls;
    }

    void next() {
        calls++;
        Supplier<? extends RuntimeException> failure = failures.poll();
        if (failure != null) {
            throw failure.get();
        }
    }
}
