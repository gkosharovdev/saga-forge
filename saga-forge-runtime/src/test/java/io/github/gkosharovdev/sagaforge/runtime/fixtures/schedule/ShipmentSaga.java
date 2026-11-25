package io.github.gkosharovdev.sagaforge.runtime.fixtures.schedule;

import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;
import io.github.gkosharovdev.sagaforge.core.command.CommandGateway;
import io.github.gkosharovdev.sagaforge.core.command.ScheduledCommandId;
import io.github.gkosharovdev.sagaforge.core.lifecycle.SagaContext;
import java.time.Instant;

/** Schedules and cancels a command through {@code CommandGateway.schedule}/{@code cancel} (specs/017 US4). */
@Saga
public final class ShipmentSaga {

    @SagaId
    private String sagaId;

    private SagaContext context;

    private CommandGateway commandGateway;

    @StartSaga
    @SagaStep(associationProperty = "shipmentId")
    public void onBooked(ShipmentEvents.Booked event) {
        ScheduledCommandId id = commandGateway.schedule(new ShipmentCommands.Ship(event.shipmentId()), event.at());
        context.putState("scheduled", id.value());
        if (event.cancelAtOnce()) {
            context.putState("cancelledAtOnce", Boolean.toString(commandGateway.cancel(id)));
        }
    }

    @SagaStep(associationProperty = "shipmentId")
    public void onWithdrawn(ShipmentEvents.Withdrawn event) {
        ScheduledCommandId id = new ScheduledCommandId(context.getState("scheduled", String.class).orElseThrow());
        context.putState("withdrawn", Boolean.toString(commandGateway.cancel(id)));
    }

    @SagaStep(associationProperty = "shipmentId")
    public void onMisused(ShipmentEvents.Misused event) {
        commandGateway.schedule(new ShipmentCommands.Note(event.shipmentId()), Instant.EPOCH);
    }

    @EndSaga
    @SagaStep(associationProperty = "shipmentId")
    public void onShipped(ShipmentEvents.Shipped event) {
    }
}
