package io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures;

import io.github.gkosharovdev.sagaforge.core.annotation.Command;

/** Sent by {@link PackingSaga}; handled in process by {@link ShippingService}. */
@Command
public record PackParcel(String orderId) {}
