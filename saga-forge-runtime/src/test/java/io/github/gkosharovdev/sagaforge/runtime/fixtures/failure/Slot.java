package io.github.gkosharovdev.sagaforge.runtime.fixtures.failure;

import io.github.gkosharovdev.sagaforge.core.annotation.Aggregate;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;
import io.github.gkosharovdev.sagaforge.core.event.DomainEvents;
import java.util.Optional;

/**
 * Aggregate with an instance-method handler, loaded and saved through {@link SlotRepository}. Reserves itself, fails if
 * {@link FaultPoint#SLOT_HANDLER} is armed, then records {@link SlotReserved}.
 */
@Aggregate
public final class Slot {

    @AggregateIdentifier
    private final String slotId;
    private final FaultPlan plan;
    private String reservedFor;

    public Slot(String slotId, FaultPlan plan) {
        this.slotId = slotId;
        this.plan = plan;
    }

    /** The slot id the saga reserves for a parcel. */
    public static String slotIdFor(String parcelId) {
        return "slot-" + parcelId;
    }

    @CommandHandler
    public void handle(ReserveSlot command) {
        reservedFor = command.parcelId();
        plan.throwIfArmed(FaultPoint.SLOT_HANDLER);
        DomainEvents.record(new SlotReserved(slotId, command.parcelId()));
    }

    public String slotId() {
        return slotId;
    }

    /** The parcel holding this slot, if any. */
    public Optional<String> reservedFor() {
        return Optional.ofNullable(reservedFor);
    }

    Slot copy() {
        Slot copy = new Slot(slotId, plan);
        copy.reservedFor = reservedFor;
        return copy;
    }
}
