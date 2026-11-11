package io.github.gkosharovdev.sagaforge.spring.events;

import org.springframework.context.ApplicationEventPublisher;

import io.github.gkosharovdev.sagaforge.core.spi.Outbox;
import io.github.gkosharovdev.sagaforge.core.spi.OutgoingMessages;

/**
 * Publishes every domain event recorded in a unit of work through Spring's {@link ApplicationEventPublisher}, inside
 * the unit of work's transaction ({@code specs/008-spring-boot-starter} FR-018a, contracts/application-events.md).
 * Decorates the transport's {@link Outbox}: same timing, same command support.
 *
 * <p>Spring's semantics then apply. {@code @TransactionalEventListener}s and Spring Modulith's
 * {@code @ApplicationModuleListener}s run after the commit and never on a rollback; Spring Modulith's registry stores a
 * publication per such listener in the same transaction. A plain {@code @EventListener} runs synchronously inside the
 * unit of work, and its failure rolls the unit of work back.</p>
 */
public final class ApplicationEventPublishingOutbox implements Outbox {

    private final Outbox delegate;
    private final ApplicationEventPublisher publisher;

    public ApplicationEventPublishingOutbox(Outbox delegate, ApplicationEventPublisher publisher) {
        this.delegate = delegate;
        this.publisher = publisher;
    }

    /** @return the decorated outbox */
    public Outbox delegate() {
        return delegate;
    }

    @Override
    public Timing timing() {
        return delegate.timing();
    }

    @Override
    public boolean supportsCommands() {
        return delegate.supportsCommands();
    }

    @Override
    public boolean observesRecordedEvents() {
        return true;
    }

    @Override
    public void store(OutgoingMessages batch) {
        if (!batch.events().isEmpty() || !batch.commands().isEmpty() || delegate.observesRecordedEvents()) {
            delegate.store(batch);
        }
        batch.recordedEvents().forEach(publisher::publishEvent);
    }

    @Override
    public void committed(OutgoingMessages batch) {
        delegate.committed(batch);
    }
}
