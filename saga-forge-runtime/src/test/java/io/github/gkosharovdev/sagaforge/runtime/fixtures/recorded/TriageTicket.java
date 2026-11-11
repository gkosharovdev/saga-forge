package io.github.gkosharovdev.sagaforge.runtime.fixtures.recorded;


import io.github.gkosharovdev.sagaforge.core.annotation.Command;

/** Sent by {@link TicketSaga}; handled in the unit of work by {@link TriageService}. */
@Command
public record TriageTicket(String ticketId) {
}
