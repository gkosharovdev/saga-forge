package io.github.gkosharovdev.sagaforge.spring.events;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The events Spring already delivered to its listeners and {@link InboundSagaEventListener}
 * recorded into the unit of work running on the calling thread ({@code specs/014-publish-once}
 * research R2): {@link ApplicationEventPublishingOutbox} does not publish them to Spring a second
 * time. Held by identity, so two equal events published separately are told apart.
 *
 * <p>Thread-bound. Cleared when the outbox has stored the unit of work's batch, and after the
 * Spring transaction the first event was added in completes, so a rolled-back unit of work leaves
 * nothing behind.
 */
final class AlreadyPublishedEvents {

  private static final ThreadLocal<Set<Object>> EVENTS = new ThreadLocal<>();

  private AlreadyPublishedEvents() {}

  /** Notes {@code event} for the unit of work on the calling thread. */
  static void add(Object event) {
    Set<Object> events = EVENTS.get();
    if (events == null) {
      events = Collections.newSetFromMap(new IdentityHashMap<>());
      EVENTS.set(events);
      if (TransactionSynchronizationManager.isSynchronizationActive()) {
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
              @Override
              public void afterCompletion(int status) {
                EVENTS.remove();
              }
            });
      }
    }
    events.add(event);
  }

  /**
   * @return {@code true} if {@code event} itself (by identity) was noted on the calling thread
   */
  static boolean contains(Object event) {
    Set<Object> events = EVENTS.get();
    return events != null && events.contains(event);
  }

  /** Forgets every event noted on the calling thread. */
  static void clear() {
    EVENTS.remove();
  }
}
