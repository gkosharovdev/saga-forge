package io.github.gkosharovdev.sagaforge.spring.events;

import io.github.gkosharovdev.sagaforge.core.event.EventPublisher;
import io.github.gkosharovdev.sagaforge.core.spi.DomainEventScope;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Saga Forge's {@link EventPublisher} that also publishes to Spring, so that an event published
 * through it reaches Spring listeners as one recorded in a unit of work does ({@code
 * specs/014-publish-once} contracts/inbound-route.md EP1 to EP3; {@code
 * saga-forge.application-events.mode=in-transaction}).
 *
 * <ul>
 *   <li>Inside a unit of work: delegates only. The event is recorded, and the unit of work
 *       publishes its recorded events to Spring ({@link ApplicationEventPublishingOutbox}).
 *   <li>Otherwise: delegates (the event is stored for sagas, in the caller's transaction when there
 *       is one), then publishes it through Spring in the same transaction. A synchronous listener's
 *       failure propagates, so the caller's transaction, and the stored event with it, rolls back.
 * </ul>
 *
 * <p>Decorates the starter's own {@code EventPublisher} bean only; an application-defined one is
 * left alone.
 */
public final class ApplicationEventPublishingEventPublisher implements EventPublisher {

  private final EventPublisher delegate;
  private final ApplicationEventPublisher publisher;

  /**
   * @param delegate the runtime's event publisher
   * @param publisher Spring's publisher
   */
  public ApplicationEventPublishingEventPublisher(
      EventPublisher delegate, ApplicationEventPublisher publisher) {
    if (delegate == null || publisher == null) {
      throw new IllegalArgumentException("delegate and publisher must not be null");
    }
    this.delegate = delegate;
    this.publisher = publisher;
  }

  /**
   * @return the decorated publisher
   */
  public EventPublisher delegate() {
    return delegate;
  }

  @Override
  public void publish(Object domainEvent) {
    boolean inUnitOfWork = DomainEventScope.current().isPresent();
    delegate.publish(domainEvent);
    if (!inUnitOfWork) {
      PublishingGuard.run(() -> publisher.publishEvent(domainEvent));
    }
  }
}
