package io.github.gkosharovdev.sagaforge.engine.fixtures.compensation;

import io.github.gkosharovdev.sagaforge.core.failure.TransientFailure;

/** The courier service is busy right now: retrying may help. */
public final class CourierBusy extends RuntimeException implements TransientFailure {

    public CourierBusy() {
        super("courier service busy");
    }
}
