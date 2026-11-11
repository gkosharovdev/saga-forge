package io.github.gkosharovdev.sagaforge.runtime.fixtures.idempotency;

import io.github.gkosharovdev.sagaforge.core.annotation.Aggregate;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;
import io.github.gkosharovdev.sagaforge.core.command.CommandId;

/** Aggregate with a creation handler and an instance handler, both taking the command identity. */
@Aggregate
public final class Shipment {

    @AggregateIdentifier
    private final String shipmentId;
    private final CommandId createdBy;
    private CommandId labelledBy;

    @CommandHandler
    public Shipment(CreateShipment command, CommandId commandId) {
        this.shipmentId = command.shipmentId();
        this.createdBy = commandId;
    }

    @CommandHandler
    public void handle(LabelShipment command, CommandId commandId) {
        labelledBy = commandId;
    }

    public String shipmentId() {
        return shipmentId;
    }

    public CommandId createdBy() {
        return createdBy;
    }

    public CommandId labelledBy() {
        return labelledBy;
    }

    /** The shipment id {@link ChargeSaga} uses for {@code orderId}. */
    public static String shipmentIdFor(String orderId) {
        return "shipment-" + orderId;
    }
}
