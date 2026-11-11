package io.github.gkosharovdev.sagaforge.runtime.fixtures.idempotency;

import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;
import io.github.gkosharovdev.sagaforge.core.event.DomainEvents;

/** Records the same {@link OrderPacked} twice, so one unit of work sees a repeated event for one step. */
public final class Repacker {

    @CommandHandler
    public void handle(Repack command) {
        DomainEvents.record(new OrderPacked(command.orderId()));
        DomainEvents.record(new OrderPacked(command.orderId()));
    }
}
