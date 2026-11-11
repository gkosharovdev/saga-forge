package io.github.gkosharovdev.sagaforge.runtime.fixtures.startup;

import io.github.gkosharovdev.sagaforge.core.execution.harness.RecordingEventDispatcher;
import io.github.gkosharovdev.sagaforge.core.spi.EventDispatcher;
import java.util.List;

/** A dispatcher whose {@code registerSagaType} throws for one saga class and records the others. */
public final class RejectingDispatcher implements EventDispatcher {

    /** The message of the rejection. */
    public static final String REJECTION = "no topic configured";

    private final Class<?> rejected;
    private final RecordingEventDispatcher recording = new RecordingEventDispatcher();

    public RejectingDispatcher(Class<?> rejected) {
        this.rejected = rejected;
    }

    @Override
    public void dispatch(Object domainEvent) {
        recording.dispatch(domainEvent);
    }

    @Override
    public void registerSagaType(Class<?> sagaClass) {
        if (sagaClass == rejected) {
            throw new IllegalStateException(REJECTION);
        }
        recording.registerSagaType(sagaClass);
    }

    /** The saga types accepted so far. */
    public List<Class<?>> registeredSagaTypes() {
        return recording.registeredSagaTypes();
    }
}
