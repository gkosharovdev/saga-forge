package io.github.gkosharovdev.sagaforge.runtime.fixtures.recorded;


import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;

/** Starts {@link TicketSaga}. */
@DomainEvent(aggregate = Ticket.class)
public record TicketOpened(@AggregateIdentifier String ticketId) {
}
