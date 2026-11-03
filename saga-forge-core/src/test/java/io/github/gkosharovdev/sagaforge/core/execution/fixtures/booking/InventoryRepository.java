package io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateLoader;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateSaver;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.Snapshottable;

/**
 * Client repository for {@link Inventory}: a plain class with an {@code @AggregateLoader} and an
 * {@code @AggregateSaver}, backed by its own map. Like a persistence context, it hands out the stored instance
 * itself, so a failure armed on {@code load(id)} fires inside the next handler call on that inventory.
 *
 * <p>{@link #snapshot()} copies every inventory; {@link #restore(Object)} replaces the stored instances with
 * fresh copies, so references obtained before a rollback are stale: re-read with {@link #load(String)}.
 * {@link #saveCount()} counts {@code save} calls and is not part of the snapshot.</p>
 */
public class InventoryRepository implements Snapshottable {

    private final Map<String, Inventory> inventories = new LinkedHashMap<>();
    private int saveCount;

    /**
     * Loads an inventory by id.
     *
     * @return the stored inventory, or {@code null} if none exists; the gateway reports {@code null} as
     *         {@code AggregateNotFoundException}
     */
    @AggregateLoader
    public Inventory load(String inventoryId) {
        return inventories.get(inventoryId);
    }

    /** Stores the inventory under its id and counts the call. */
    @AggregateSaver
    public void save(Inventory inventory) {
        if (inventory == null) {
            throw new IllegalArgumentException("inventory must not be null");
        }
        inventories.put(inventory.inventoryId(), inventory);
        saveCount++;
    }

    /** Seeds an inventory without counting a save. */
    public void add(Inventory inventory) {
        if (inventory == null) {
            throw new IllegalArgumentException("inventory must not be null");
        }
        inventories.put(inventory.inventoryId(), inventory);
    }

    /** Number of {@link #save(Inventory)} calls since construction or {@link #reset()}. */
    public int saveCount() {
        return saveCount;
    }

    /** Clears all inventories and the save count. */
    public void reset() {
        inventories.clear();
        saveCount = 0;
    }

    @Override
    public Object snapshot() {
        return new State(inventories.values().stream().map(Inventory::copy).toList());
    }

    @Override
    public void restore(Object snapshot) {
        if (!(snapshot instanceof State state)) {
            throw new IllegalArgumentException("Not an InventoryRepository snapshot: " + snapshot);
        }
        inventories.clear();
        state.inventories().forEach(inventory -> inventories.put(inventory.inventoryId(), inventory.copy()));
    }

    private record State(List<Inventory> inventories) {
    }
}
