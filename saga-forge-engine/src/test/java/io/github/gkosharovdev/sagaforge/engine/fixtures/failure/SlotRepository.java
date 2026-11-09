package io.github.gkosharovdev.sagaforge.engine.fixtures.failure;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateLoader;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateSaver;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.Snapshottable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Client loader and saver of {@link Slot}; they fail if {@link FaultPoint#SLOT_LOADER} or {@code SLOT_SAVER} is
 * armed.
 */
public final class SlotRepository implements Snapshottable {

    private final FaultPlan plan;
    private final Map<String, Slot> slots = new LinkedHashMap<>();

    public SlotRepository(FaultPlan plan) {
        this.plan = plan;
    }

    /** Seeds a free slot. */
    public void add(String slotId) {
        slots.put(slotId, new Slot(slotId, plan));
    }

    @AggregateLoader
    public Slot load(String slotId) {
        plan.throwIfArmed(FaultPoint.SLOT_LOADER);
        return slots.get(slotId);
    }

    @AggregateSaver
    public void save(Slot slot) {
        plan.throwIfArmed(FaultPoint.SLOT_SAVER);
        slots.put(slot.slotId(), slot);
    }

    /** Slot id to the parcel holding it, for every reserved slot. */
    public Map<String, String> reservations() {
        Map<String, String> reserved = new LinkedHashMap<>();
        slots.values().forEach(slot -> slot.reservedFor().ifPresent(parcel -> reserved.put(slot.slotId(), parcel)));
        return Map.copyOf(reserved);
    }

    @Override
    public Object snapshot() {
        return slots.values().stream().map(Slot::copy).toList();
    }

    @Override
    public void restore(Object snapshot) {
        if (!(snapshot instanceof List<?> state)) {
            throw new IllegalArgumentException("Not a SlotRepository snapshot: " + snapshot);
        }
        slots.clear();
        state.forEach(slot -> slots.put(((Slot) slot).slotId(), ((Slot) slot).copy()));
    }
}
