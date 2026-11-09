package io.github.gkosharovdev.sagaforge.engine.fixtures.fanout;

import io.github.gkosharovdev.sagaforge.core.annotation.Aggregate;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;

/** Marker aggregate every fan-out event names; {@link NoteService} keeps the notes. */
@Aggregate
public final class Case {

    @AggregateIdentifier
    private String caseId;
}
