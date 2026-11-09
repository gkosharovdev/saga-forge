package io.github.gkosharovdev.sagaforge.engine.fixtures.retry;

import io.github.gkosharovdev.sagaforge.core.failure.TransientFailure;

/** A failure marked transient, not listed in {@code retryOn}. */
public final class FlakyNetwork extends RuntimeException implements TransientFailure {

    public FlakyNetwork(String message) {
        super(message);
    }
}
