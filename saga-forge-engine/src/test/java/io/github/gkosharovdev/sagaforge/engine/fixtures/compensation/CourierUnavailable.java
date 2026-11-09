package io.github.gkosharovdev.sagaforge.engine.fixtures.compensation;

import io.github.gkosharovdev.sagaforge.core.failure.FinalFailure;

/** The courier service is permanently unavailable: retrying cannot help. */
public final class CourierUnavailable extends RuntimeException implements FinalFailure {

    public CourierUnavailable() {
        super("courier service permanently unavailable");
    }
}
