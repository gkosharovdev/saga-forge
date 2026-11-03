package io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;
import io.github.gkosharovdev.sagaforge.core.event.DomainEvents;

/**
 * Service-hosted handler for {@link ScheduleShipment}: changes a {@link Shipment} and records
 * {@link ShipmentScheduled}. A plain class with no Saga Forge supertype; {@link Snapshottable} is a
 * test-only fixture contract.
 */
public class ShipmentService implements Snapshottable {

    private final Map<String, Shipment> shipments = new LinkedHashMap<>();
    private final FaultSwitch faults = new FaultSwitch();

    /** The {@link Shipment} id this service uses for an order. */
    public static String shipmentIdFor(String orderId) {
        return "shipment-" + orderId;
    }

    /** Schedules the shipment, then throws if a failure is armed, otherwise records {@link ShipmentScheduled}. */
    @CommandHandler
    public void handle(ScheduleShipment command) {
        String shipmentId = shipmentIdFor(command.orderId());
        shipments.put(shipmentId, new Shipment(shipmentId));
        faults.throwIfArmed();
        DomainEvents.record(new ShipmentScheduled(shipmentId, command.orderId(), command.invoiceId()));
    }

    /** Arms a one-shot failure with a default {@link IllegalStateException}. */
    public void failNext() {
        failNext(new IllegalStateException("ShipmentService failure injected"));
    }

    /** Arms a one-shot failure: the next {@code handle} call throws {@code failure} after its change. */
    public void failNext(RuntimeException failure) {
        faults.arm(failure);
    }

    /** Ids of the shipments changed so far, in change order. */
    public Set<String> changedAggregates() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(shipments.keySet()));
    }

    /** Clears all shipments and disarms any pending failure. */
    public void reset() {
        shipments.clear();
        faults.disarm();
    }

    @Override
    public Object snapshot() {
        return new State(List.copyOf(shipments.values()));
    }

    @Override
    public void restore(Object snapshot) {
        if (!(snapshot instanceof State state)) {
            throw new IllegalArgumentException("Not a ShipmentService snapshot: " + snapshot);
        }
        shipments.clear();
        state.shipments().forEach(shipment -> shipments.put(shipment.id(), shipment));
    }

    private record State(List<Shipment> shipments) {
    }
}
