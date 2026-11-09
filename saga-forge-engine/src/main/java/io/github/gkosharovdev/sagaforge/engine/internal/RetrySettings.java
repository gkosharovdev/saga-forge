package io.github.gkosharovdev.sagaforge.engine.internal;

import java.time.Duration;

/**
 * The engine-wide retry settings (005 research §7, §10). Public only so the engine's builder can create it; not API.
 *
 * @param maxTotalBackoff the most a step's retry policy may wait in total over all its attempts; positive
 * @param pause           how the engine waits between attempts
 */
public record RetrySettings(Duration maxTotalBackoff, Pause pause) {

    /** The default {@link #maxTotalBackoff}: 30 seconds. */
    public static final Duration DEFAULT_MAX_TOTAL_BACKOFF = Duration.ofSeconds(30);

    /** Waits between attempts; interruptible. */
    @FunctionalInterface
    public interface Pause {

        void pause(Duration wait) throws InterruptedException;
    }

    public RetrySettings {
        if (maxTotalBackoff == null || maxTotalBackoff.isNegative() || maxTotalBackoff.isZero()) {
            throw new IllegalArgumentException("maxTotalBackoff must be positive, but is " + maxTotalBackoff);
        }
        if (pause == null) {
            throw new IllegalArgumentException("pause must not be null");
        }
    }

    /** @return the default maximum with a sleeping pause */
    public static RetrySettings defaults() {
        return new RetrySettings(DEFAULT_MAX_TOTAL_BACKOFF, wait -> Thread.sleep(wait.toMillis()));
    }
}
