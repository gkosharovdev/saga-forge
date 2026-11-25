package io.github.gkosharovdev.sagaforge.runtime.fixtures.commandfailure;

import io.github.gkosharovdev.sagaforge.core.annotation.Command;
import io.github.gkosharovdev.sagaforge.core.annotation.CommandDelivery;

/** Sent by the compensation {@link ParcelSaga#refund}. An outbox command. */
@Command(delivery = CommandDelivery.OUTBOX)
public record RefundParcel(String parcelId) {}
