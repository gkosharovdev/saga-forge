package io.github.gkosharovdev.sagaforge.engine.fixtures.fanout;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;

/**
 * Start event of {@link AuditSaga}.
 *
 * @param caseId the case and the association value
 * @param mode   what the {@link AddNote} the start step sends makes {@link NoteService} record
 */
@DomainEvent(aggregate = Case.class)
public record CaseOpened(@AggregateIdentifier String caseId, AddNote.Mode mode) {
}
