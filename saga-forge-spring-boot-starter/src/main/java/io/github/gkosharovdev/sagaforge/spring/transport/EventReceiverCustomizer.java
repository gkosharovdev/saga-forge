package io.github.gkosharovdev.sagaforge.spring.transport;

import io.github.gkosharovdev.sagaforge.core.spi.EventReceiver;

/**
 * Optional bean that wraps the {@link EventReceiver} the transport delivers events to, for example
 * to add tracing or metrics around each delivery. Applied by {@link TransportLifecycle} in bean
 * order; without one, events go straight to the {@code SagaRuntime}.
 *
 * <p>A wrapper should override both {@code receive} methods and forward {@link
 * EventReceiver#receive(Object, String)} with its source ({@code specs/014-publish-once}); one that
 * forwards only {@code receive(Object)} still works, but the instance that recorded a fanned-out
 * event then reports it as {@code DUPLICATE}.
 */
@FunctionalInterface
public interface EventReceiverCustomizer {

  /**
   * @param receiver the receiver so far, at first the runtime; non-null
   * @return the receiver to use instead; non-null
   */
  EventReceiver customize(EventReceiver receiver);
}
