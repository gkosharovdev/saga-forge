package io.github.gkosharovdev.sagaforge.engine.fixtures.failure;

import io.github.gkosharovdev.sagaforge.core.annotation.Command;

/** Sent by the start step; handled by {@link FeeService}. */
@Command
public record ChargeFee(String parcelId, long weight) {
}
