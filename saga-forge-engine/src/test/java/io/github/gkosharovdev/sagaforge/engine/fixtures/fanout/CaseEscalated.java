package io.github.gkosharovdev.sagaforge.engine.fixtures.fanout;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;

/** Fan-out fixture event; see {@link AuditSaga} and {@link ReviewSaga} for the steps it triggers. */
@DomainEvent(aggregate = Case.class)
public record CaseEscalated(@AggregateIdentifier String caseId) {
}
