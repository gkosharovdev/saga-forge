package io.github.gkosharovdev.sagaforge.spring.transport;

import io.github.gkosharovdev.sagaforge.core.spi.EventReceiver;

/**
 * Optional bean that wraps the {@link EventReceiver} the transport delivers events to, for example to add tracing or
 * metrics around each delivery. Applied by {@link TransportLifecycle} in bean order; without one, events go straight
 * to the {@code SagaEngine}.
 */
@FunctionalInterface
public interface EventReceiverCustomizer {

    /**
     * @param receiver the receiver so far, at first the engine; non-null
     * @return the receiver to use instead; non-null
     */
    EventReceiver customize(EventReceiver receiver);
}
