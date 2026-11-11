package io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures;


import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;
import io.github.gkosharovdev.sagaforge.core.command.CommandId;
import io.github.gkosharovdev.sagaforge.core.event.DomainEvents;

/**
 * Billing module handler of the logged {@link ChargeCard}: notes each charge with its {@link CommandId} and records
 * {@link CardCharged}. {@link #failNext(int)} makes the next calls throw after noting. Thread-safe.
 */
public final class CardService {

    /** One handled charge. */
    public record Charge(String invoiceId, CommandId commandId, String thread) {
    }

    private final List<Charge> charges = new CopyOnWriteArrayList<>();
    private final AtomicInteger failures = new AtomicInteger();

    @CommandHandler
    public void handle(ChargeCard command, CommandId commandId) {
        charges.add(new Charge(command.invoiceId(), commandId, Thread.currentThread().getName()));
        if (failures.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            throw new IllegalStateException("card provider unavailable");
        }
        DomainEvents.record(new CardCharged(command.invoiceId()));
    }

    public void failNext(int times) {
        failures.set(times);
    }

    public List<Charge> charges() {
        return List.copyOf(charges);
    }
}
