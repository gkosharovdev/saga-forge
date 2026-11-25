package io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures;

import io.github.gkosharovdev.sagaforge.core.failure.FinalFailure;

/** A card the provider will never charge: marked {@link FinalFailure}, so the relay does not retry it. */
public final class CardDeclined extends RuntimeException implements FinalFailure {

    public CardDeclined(String invoiceId) {
        super("card declined for invoice " + invoiceId);
    }
}
