package io.github.gkosharovdev.sagaforge.runtime.fixtures.retry;

import io.github.gkosharovdev.sagaforge.runtime.RetryPause;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Records every wait instead of sleeping; can be told to be interrupted or to "crash" on a given call. */
public final class RecordingPause implements RetryPause {

    /** Thrown by a crashing pause: stands in for the process stopping while it waits. */
    public static final class Crash extends RuntimeException {

        public Crash() {
            super("process stopped while waiting to retry");
        }
    }

    private final List<Duration> waits = new ArrayList<>();
    private int interruptOnCall;
    private int crashOnCall;

    @Override
    public void pause(Duration wait) throws InterruptedException {
        waits.add(wait);
        if (waits.size() == interruptOnCall) {
            throw new InterruptedException("interrupted");
        }
        if (waits.size() == crashOnCall) {
            throw new Crash();
        }
    }

    /** The {@code call}-th pause (1-based) is interrupted. */
    public RecordingPause interruptOnCall(int call) {
        this.interruptOnCall = call;
        return this;
    }

    /** The {@code call}-th pause (1-based) throws {@link Crash}. */
    public RecordingPause crashOnCall(int call) {
        this.crashOnCall = call;
        return this;
    }

    public List<Duration> waits() {
        return List.copyOf(waits);
    }
}
