package io.github.gkosharovdev.sagaforge.runtime.fixtures.startup;

import io.github.gkosharovdev.sagaforge.core.annotation.Aggregate;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;
import io.github.gkosharovdev.sagaforge.core.event.DomainEvents;

/** An aggregate with an instance-method handler and no {@code @AggregateLoader} anywhere (Property 21). */
@Aggregate
public final class UnloadableAggregate {

    @AggregateIdentifier
    private final String unloadableId;

    public UnloadableAggregate(String unloadableId) {
        this.unloadableId = unloadableId;
    }

    @CommandHandler
    public void handle(PokeUnloadable command) {
        DomainEvents.record(new UnloadablePoked(unloadableId));
    }
}
