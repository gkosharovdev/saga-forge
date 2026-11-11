package io.github.gkosharovdev.sagaforge.runtime.fixtures.idempotency;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateLoader;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateSaver;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Non-transactional shipment store that keeps every saved version. */
public final class ShipmentRepository {

    private final Map<String, Shipment> shipments = new LinkedHashMap<>();
    private final List<Shipment> saves = new ArrayList<>();

    @AggregateLoader
    public Shipment load(String shipmentId) {
        return shipments.get(shipmentId);
    }

    @AggregateSaver
    public void save(Shipment shipment) {
        shipments.put(shipment.shipmentId(), shipment);
        saves.add(shipment);
    }

    public Map<String, Shipment> shipments() {
        return Map.copyOf(shipments);
    }

    public List<Shipment> saves() {
        return List.copyOf(saves);
    }
}
