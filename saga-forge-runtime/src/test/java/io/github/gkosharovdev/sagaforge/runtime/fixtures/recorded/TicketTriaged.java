package io.github.gkosharovdev.sagaforge.runtime.fixtures.recorded;


import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;

/** Recorded by {@link TriageService}; only the same {@link TicketSaga} instance reacts, in process. */
@DomainEvent(aggregate = Ticket.class)
public record TicketTriaged(@AggregateIdentifier String ticketId) {
}
