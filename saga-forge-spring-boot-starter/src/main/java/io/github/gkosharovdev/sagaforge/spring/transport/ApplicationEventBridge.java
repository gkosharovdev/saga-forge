package io.github.gkosharovdev.sagaforge.spring.transport;

import java.lang.System.Logger.Level;

import org.springframework.context.ApplicationEventPublisher;

import io.github.gkosharovdev.sagaforge.core.spi.store.StoredMessage;
import io.github.gkosharovdev.sagaforge.transport.inmemory.InMemoryTransport;

/**
 * Re-publishes every event the transport delivered and marked consumed as a Spring application event, so non-saga
 * {@code @EventListener}s in the modulith can observe it ({@code specs/007-in-memory-transport} FR-027). Runs outside
 * any transaction; a listener's failure is logged and never affects the message.
 */
public final class ApplicationEventBridge {

    private static final System.Logger LOGGER = System.getLogger("io.github.gkosharovdev.sagaforge.transport");

    private final ApplicationEventPublisher publisher;

    public ApplicationEventBridge(InMemoryTransport transport, ApplicationEventPublisher publisher) {
        this.publisher = publisher;
        transport.onDelivered(this::publish);
    }

    private void publish(StoredMessage message) {
        try {
            publisher.publishEvent(message.payload());
        } catch (RuntimeException failure) {
            LOGGER.log(Level.WARNING, "saga.transport.bridgeListenerFailed message=" + message.messageId(), failure);
        }
    }
}
