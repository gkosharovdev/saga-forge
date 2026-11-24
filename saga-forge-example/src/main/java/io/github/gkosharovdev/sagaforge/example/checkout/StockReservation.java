package io.github.gkosharovdev.sagaforge.example.checkout;

import io.github.gkosharovdev.sagaforge.core.annotation.Aggregate;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;

/**
 * A stock reservation held by the Inventory service. Its state lives in that other service; the class only names the aggregate its events belong to.
 */
@Aggregate
public class StockReservation {

    @AggregateIdentifier
    private String reservationId;
}
