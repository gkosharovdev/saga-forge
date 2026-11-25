package io.github.gkosharovdev.sagaforge.runtime.fixtures.commandfailure;

import io.github.gkosharovdev.sagaforge.core.annotation.Command;
import io.github.gkosharovdev.sagaforge.core.annotation.CommandDelivery;

/** Sent by {@link ParcelSaga#pack}; the step whose command fails in the 012 tests. An outbox command. */
@Command(delivery = CommandDelivery.OUTBOX)
public record ShipParcel(String parcelId) {
}
