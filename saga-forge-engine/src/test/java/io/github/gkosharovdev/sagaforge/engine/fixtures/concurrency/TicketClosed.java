package io.github.gkosharovdev.sagaforge.engine.fixtures.concurrency;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;

/** Concurrency-fixture event; see {@link SignalSaga} for the step it triggers. */
@DomainEvent(aggregate = Ticket.class)
public record TicketClosed(@AggregateIdentifier String ticketId) {
}
