package io.github.gkosharovdev.sagaforge.example.checkout;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;
import io.github.gkosharovdev.sagaforge.core.event.DomainEvents;
import io.github.gkosharovdev.sagaforge.example.checkout.commands.CommitStockCommand;
import io.github.gkosharovdev.sagaforge.example.checkout.commands.ReleaseStockCommand;
import io.github.gkosharovdev.sagaforge.example.checkout.commands.ReserveStockCommand;
import io.github.gkosharovdev.sagaforge.example.checkout.events.StockReservedEvent;
import io.github.gkosharovdev.sagaforge.example.checkout.events.StockUnavailableEvent;

/**
 * Stands in for the remote Inventory service. In production this class is a client of Inventory's API and Inventory's
 * answer arrives later as an event over the transport; here it answers in process by recording the same events. Both
 * ways, the saga sees only commands going out and events coming back.
 */
public class InventoryService {

    /** Where a reservation stands. */
    public enum State { RESERVED, COMMITTED, RELEASED }

    private final Map<String, Integer> available = new HashMap<>();
    private final Map<String, State> reservations = new LinkedHashMap<>();
    private final Map<String, List<OrderLine>> reservedLines = new HashMap<>();

    /** Puts {@code quantity} of {@code sku} on the shelf. */
    public InventoryService stock(String sku, int quantity) {
        available.merge(sku, quantity, Integer::sum);
        return this;
    }

    /** Holds every line of the order, or none of them if one is short. */
    @CommandHandler
    public void handle(ReserveStockCommand command) {
        String reservationId = reservationIdOf(command.orderId());
        if (reservations.containsKey(reservationId)) {
            DomainEvents.record(new StockReservedEvent(reservationId, command.orderId())); // a redelivered command
            return;
        }
        for (OrderLine line : command.lines()) {
            if (available.getOrDefault(line.sku(), 0) < line.quantity()) {
                DomainEvents.record(new StockUnavailableEvent(reservationId, command.orderId(), line.sku()));
                return;
            }
        }
        command.lines().forEach(line -> available.merge(line.sku(), -line.quantity(), Integer::sum));
        reservations.put(reservationId, State.RESERVED);
        reservedLines.put(reservationId, List.copyOf(command.lines()));
        DomainEvents.record(new StockReservedEvent(reservationId, command.orderId()));
    }

    @CommandHandler
    public void handle(CommitStockCommand command) {
        reservations.computeIfPresent(command.reservationId(),
                (id, state) -> state == State.RESERVED ? State.COMMITTED : state);
    }

    /**
     * Gives the stock back: a reservation that still holds goes back on the shelf; committed stock is booked back in
     * when the returned parcel reaches the warehouse (not modelled here). An order with no reservation, or one already
     * released, changes nothing, so the command is safe to send on every failure path and to redeliver.
     */
    @CommandHandler
    public void handle(ReleaseStockCommand command) {
        String reservationId = reservationIdOf(command.orderId());
        State state = reservations.get(reservationId);
        if (state == State.RESERVED) {
            reservedLines.get(reservationId).forEach(line -> available.merge(line.sku(), line.quantity(), Integer::sum));
        }
        if (state != null) {
            reservations.put(reservationId, State.RELEASED);
        }
    }

    private static String reservationIdOf(String orderId) {
        return "reservation-" + orderId;
    }

    public State reservation(String reservationId) {
        return reservations.get(reservationId);
    }

    public int available(String sku) {
        return available.getOrDefault(sku, 0);
    }
}
