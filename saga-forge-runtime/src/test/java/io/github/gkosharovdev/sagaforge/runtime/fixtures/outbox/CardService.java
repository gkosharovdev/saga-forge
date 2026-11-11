package io.github.gkosharovdev.sagaforge.runtime.fixtures.outbox;


import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;
import io.github.gkosharovdev.sagaforge.core.command.CommandId;
import io.github.gkosharovdev.sagaforge.core.event.DomainEvents;

/** Handles the logged {@link ChargeCard}: notes the identity and records {@link CardCharged}. */
public final class CardService {

    private final List<CommandId> charged = new CopyOnWriteArrayList<>();
    private volatile RuntimeException nextFailure;

    @CommandHandler
    public void handle(ChargeCard command, CommandId commandId) {
        charged.add(commandId);
        RuntimeException failure = nextFailure;
        if (failure != null) {
            nextFailure = null;
            throw failure;
        }
        DomainEvents.record(new CardCharged(command.invoiceId()));
    }

    public void failNext(RuntimeException failure) {
        nextFailure = failure;
    }

    public List<CommandId> charged() {
        return List.copyOf(charged);
    }
}
