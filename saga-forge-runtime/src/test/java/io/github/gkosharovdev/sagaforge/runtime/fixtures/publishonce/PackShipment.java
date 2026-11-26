package io.github.gkosharovdev.sagaforge.runtime.fixtures.publishonce;

import io.github.gkosharovdev.sagaforge.core.annotation.Command;

/** Sent by {@link PackingSaga}'s start step; handled by {@link PackingService}. */
@Command
public record PackShipment(String orderId) {}
