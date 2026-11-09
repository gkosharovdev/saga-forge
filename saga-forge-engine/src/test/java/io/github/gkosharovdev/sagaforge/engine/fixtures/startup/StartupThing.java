package io.github.gkosharovdev.sagaforge.engine.fixtures.startup;

import io.github.gkosharovdev.sagaforge.core.annotation.Aggregate;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;

/** Marker aggregate named by the events of {@link SagaWithoutNoArgConstructor}. */
@Aggregate
public final class StartupThing {

    @AggregateIdentifier
    private String thingId;
}
