package io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.gkosharovdev.sagaforge.core.annotation.Aggregate;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;
import io.github.gkosharovdev.sagaforge.core.event.DomainEvents;

/**
 * Plain {@code @Aggregate} with instance-method command handlers: it extends and implements no Saga Forge type
 * (spec US2 scenario 1). The gateway loads it through {@link InventoryRepository#load(String)} by the command's
 * {@code @AggregateIdentifier} and saves it through {@link InventoryRepository#save(Inventory)} afterwards.
 *
 * <p>Fault injection: {@link #failNext()} / {@link #failNext(RuntimeException)} arm a one-shot failure on this
 * instance. The next {@code handle} call first applies its claim change, then throws, before recording its
 * event. The switch is not copied by {@link #copy()}.</p>
 */
@Aggregate
public class Inventory {

    @AggregateIdentifier
    private final String inventoryId;

    private final Map<String, Claim> claims = new LinkedHashMap<>();
    private final FaultSwitch faults = new FaultSwitch();

    public Inventory(String inventoryId) {
        this.inventoryId = inventoryId;
    }

    /** The claim id this inventory uses for a booking. */
    public static String claimIdFor(String bookingId) {
        return "claim-" + bookingId;
    }

    /**
     * Claims availability for a booking, then throws if a failure is armed, otherwise records
     * {@link AvailabilityClaimedEvent}.
     *
     * @throws IllegalStateException if the booking already holds a claim on this inventory
     */
    @CommandHandler
    public void handle(ClaimAvailability command) {
        String claimId = claimIdFor(command.bookingId());
        if (claims.containsKey(claimId)) {
            throw new IllegalStateException("Booking " + command.bookingId() + " already holds claim " + claimId);
        }
        claims.put(claimId, new Claim(claimId, command.bookingId(), command.checkIn(), command.checkOut()));
        faults.throwIfArmed();
        DomainEvents.record(new AvailabilityClaimedEvent(inventoryId, command.bookingId(), claimId));
    }

    /**
     * Releases a claim, then throws if a failure is armed, otherwise records {@link AvailabilityReleasedEvent}.
     *
     * @throws IllegalStateException if no such claim exists
     */
    @CommandHandler
    public void handle(ReleaseAvailability command) {
        if (claims.remove(command.claimId()) == null) {
            throw new IllegalStateException("Inventory " + inventoryId + " has no claim " + command.claimId());
        }
        faults.throwIfArmed();
        DomainEvents.record(new AvailabilityReleasedEvent(inventoryId, command.bookingId(), command.claimId()));
    }

    public String inventoryId() {
        return inventoryId;
    }

    /** Current claims, in claim order. */
    public List<Claim> claims() {
        return List.copyOf(claims.values());
    }

    /** The claim with the given id, if held. */
    public Optional<Claim> claim(String claimId) {
        return Optional.ofNullable(claims.get(claimId));
    }

    /** Arms a one-shot failure with a default {@link IllegalStateException}. */
    public void failNext() {
        failNext(new IllegalStateException("Inventory failure injected"));
    }

    /** Arms a one-shot failure: the next {@code handle} call throws {@code failure} after its change. */
    public void failNext(RuntimeException failure) {
        faults.arm(failure);
    }

    /** A deep copy of the id and claims, with no armed failure. */
    public Inventory copy() {
        Inventory copy = new Inventory(inventoryId);
        copy.claims.putAll(claims);
        return copy;
    }

    /**
     * One availability claim held by a booking.
     *
     * @param claimId   the claim's id, see {@link Inventory#claimIdFor(String)}
     * @param bookingId the booking holding it
     * @param checkIn   first night of the stay
     * @param checkOut  departure day (exclusive)
     */
    public record Claim(String claimId, String bookingId, LocalDate checkIn, LocalDate checkOut) {
    }
}
