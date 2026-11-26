package io.github.gkosharovdev.sagaforge.spring.events;

import io.github.gkosharovdev.sagaforge.core.event.DomainEvents;
import io.github.gkosharovdev.sagaforge.core.spi.DomainEventScope;
import io.github.gkosharovdev.sagaforge.core.spi.EventDispatcher;
import java.util.Set;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

/**
 * Brings saga events published through Spring's {@code ApplicationEventPublisher} into Saga Forge,
 * so that application code publishes an event once and both Spring listeners and sagas receive it
 * ({@code specs/014-publish-once} contracts/inbound-route.md IR1 to IR5). This covers events Spring
 * Data publishes from an aggregate's {@code @DomainEvents} on save.
 *
 * <p>For each published payload whose exact class some registered saga has a step for, and which
 * Saga Forge did not publish itself:
 *
 * <ul>
 *   <li>inside a saga unit of work on the publishing thread, it is recorded there, exactly as
 *       {@code DomainEvents.record} would record it: a waiting step of the same instance runs in
 *       process, the event is stored with the unit of work and discarded on rollback, and Saga
 *       Forge does not publish it to Spring again;
 *   <li>otherwise it is handed to the transport's {@link EventDispatcher}, which stores it in the
 *       active transaction, or on its own when none is active. This needs no started runtime.
 * </ul>
 *
 * <p>Synchronous, on the publishing thread; a storing failure propagates to the publisher's caller.
 * Every other event passes by untouched. Switched off with {@code
 * saga-forge.application-events.inbound.enabled=false}.
 */
public final class InboundSagaEventListener implements ApplicationListener<ApplicationEvent> {

  private final Set<Class<?>> sagaEventTypes;
  private final EventDispatcher dispatcher;

  /**
   * @param sagaEventTypes the event classes some registered saga has a step for ({@code
   *     SagaRuntime.eventTypes()})
   * @param dispatcher the transport's dispatcher, which stores the event for delivery to sagas
   */
  public InboundSagaEventListener(Set<Class<?>> sagaEventTypes, EventDispatcher dispatcher) {
    if (sagaEventTypes == null || dispatcher == null) {
      throw new IllegalArgumentException("sagaEventTypes and dispatcher must not be null");
    }
    this.sagaEventTypes = Set.copyOf(sagaEventTypes);
    this.dispatcher = dispatcher;
  }

  @Override
  public void onApplicationEvent(ApplicationEvent event) {
    if (!(event instanceof PayloadApplicationEvent<?> published) || PublishingGuard.active()) {
      return;
    }
    Object payload = published.getPayload();
    if (!sagaEventTypes.contains(payload.getClass())) {
      return;
    }
    if (DomainEventScope.current().isPresent()) {
      AlreadyPublishedEvents.add(payload);
      DomainEvents.record(payload);
    } else {
      dispatcher.dispatch(payload);
    }
  }

  @Override
  public boolean supportsAsyncExecution() {
    return false;
  }
}
