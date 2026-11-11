package io.github.gkosharovdev.sagaforge.runtime.fixtures.compensation;

import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;
import io.github.gkosharovdev.sagaforge.core.event.DomainEvents;

/** Records {@link BDone} then {@link CDone} in the running unit of work. */
public final class ChainService {

    @CommandHandler
    public void handle(Chain command) {
        DomainEvents.record(new BDone(command.orderId()));
        DomainEvents.record(new CDone(command.orderId()));
    }
}
