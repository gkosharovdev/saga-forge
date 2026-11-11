package io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures;


import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;
import io.github.gkosharovdev.sagaforge.core.event.DomainEvents;

/** Shipping module handler: books the shipment and records {@link ShipmentBooked}. Thread-safe. */
public final class ShippingService {

    private final List<String> booked = new CopyOnWriteArrayList<>();

    @CommandHandler
    public void handle(BookShipment command) {
        booked.add(command.orderId());
        DomainEvents.record(new ShipmentBooked(command.orderId()));
    }

    public List<String> booked() {
        return List.copyOf(booked);
    }
}
