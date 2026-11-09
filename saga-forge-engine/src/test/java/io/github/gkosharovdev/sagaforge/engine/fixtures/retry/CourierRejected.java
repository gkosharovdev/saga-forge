package io.github.gkosharovdev.sagaforge.engine.fixtures.retry;

import io.github.gkosharovdev.sagaforge.core.failure.FinalFailure;

/** A failure marked final: retrying cannot help. */
public final class CourierRejected extends RuntimeException implements FinalFailure {

    public CourierRejected(String message) {
        super(message);
    }
}
