package io.github.gkosharovdev.sagaforge.runtime.fixtures.recorded;


import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;
import io.github.gkosharovdev.sagaforge.core.event.DomainEvents;

/** Records {@link TicketTriaged} for every triage. */
public final class TriageService {

    @CommandHandler
    public void handle(TriageTicket command) {
        DomainEvents.record(new TicketTriaged(command.ticketId()));
    }
}
