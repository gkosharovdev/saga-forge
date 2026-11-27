package io.github.gkosharovdev.sagaforge.core.event;

import io.github.gkosharovdev.sagaforge.core.spi.DomainEventRecorder;
import io.github.gkosharovdev.sagaforge.core.spi.DomainEventScope;
import io.github.gkosharovdev.sagaforge.core.spi.model.DomainRole;
import io.github.gkosharovdev.sagaforge.core.spi.model.DomainTypes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Static facade for recording domain events inside the current unit of work.
 *
 * <p>{@link #record(Object)} can be called from any code running on the unit of work's thread (an
 * aggregate, a domain service, a command handler) without inheriting from or implementing any Saga
 * Forge type, so aggregates may be state-stored or event-sourced. The event is noted now and takes
 * effect when the unit of work commits; on rollback it is discarded.
 *
 * <p>Saga Forge does not check that a recorded event follows a real state transition. That is the
 * client's responsibility.
 *
 * <p>{@link #capture(Runnable)} is a testing aid that lets aggregates be unit-tested without the
 * framework; {@link #capture(DomainTypes, Runnable)} does the same for events marked with another
 * vocabulary.
 *
 * @see EventPublisher
 * @see DomainEventScope
 */
public final class DomainEvents {

  private DomainEvents() {}

  /**
   * Records {@code domainEvent} with the unit of work active on the calling thread.
   *
   * <p>The event must be a domain event in the vocabularies of that unit of work's runtime ({@link
   * DomainEventRecorder#domainTypes()}); with no unit of work active, in Saga Forge's own.
   *
   * @param domainEvent a domain event, for example a {@code @DomainEvent}-annotated object; must
   *     not be {@code null}
   * @throws IllegalArgumentException if {@code domainEvent} is {@code null} or not a domain event,
   *     for example {@code "{Class} is not annotated @DomainEvent"}
   * @throws IllegalStateException if no unit of work is active on the calling thread (for example,
   *     a thread the handler started itself)
   */
  public static void record(Object domainEvent) {
    if (domainEvent == null) {
      throw new IllegalArgumentException("domainEvent must not be null");
    }
    Optional<DomainEventRecorder> recorder = DomainEventScope.current();
    requireDomainEvent(
        recorder.map(DomainEventRecorder::domainTypes).orElse(DomainTypes.builtIn()), domainEvent);
    recorder
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "No unit of work is active on this thread; cannot record "
                        + domainEvent.getClass().getName()))
        .record(domainEvent);
  }

  /**
   * Runs {@code action} with a temporary recorder and returns the events it recorded, in recording
   * order.
   *
   * <p>Testing aid: lets an aggregate's behavior be asserted without the framework.
   *
   * @param action the code to run; must not be {@code null}
   * @return an unmodifiable list of the recorded events, in recording order
   * @throws IllegalArgumentException if {@code action} is {@code null}
   * @throws IllegalStateException if a unit of work is already active on the calling thread
   */
  public static List<Object> capture(Runnable action) {
    return capture(DomainTypes.builtIn(), action);
  }

  /**
   * Runs {@code action} with a temporary recorder accepting the domain events of {@code
   * domainTypes}, and returns the events it recorded, in recording order.
   *
   * <p>Testing aid: lets an aggregate marked with another vocabulary (jMolecules, for example) be
   * asserted without the framework.
   *
   * @param domainTypes the vocabularies whose domain events may be recorded; must not be {@code
   *     null}
   * @param action the code to run; must not be {@code null}
   * @return an unmodifiable list of the recorded events, in recording order
   * @throws IllegalArgumentException if an argument is {@code null}
   * @throws IllegalStateException if a unit of work is already active on the calling thread
   */
  public static List<Object> capture(DomainTypes domainTypes, Runnable action) {
    if (domainTypes == null) {
      throw new IllegalArgumentException("domainTypes must not be null");
    }
    if (action == null) {
      throw new IllegalArgumentException("action must not be null");
    }
    if (DomainEventScope.current().isPresent()) {
      throw new IllegalStateException(
          "capture cannot be used while a unit of work is active on this thread");
    }
    List<Object> recorded = new ArrayList<>();
    DomainEventScope.runWith(new CapturingRecorder(domainTypes, recorded), action);
    return Collections.unmodifiableList(recorded);
  }

  private static void requireDomainEvent(DomainTypes domainTypes, Object domainEvent) {
    if (!domainTypes.isDomainEvent(domainEvent.getClass())) {
      throw new IllegalArgumentException(
          domainTypes.notMarkedMessage(domainEvent.getClass(), DomainRole.DOMAIN_EVENT));
    }
  }

  /** The recorder {@link #capture} binds: collects the events, in the given vocabularies. */
  private record CapturingRecorder(DomainTypes domainTypes, List<Object> recorded)
      implements DomainEventRecorder {

    @Override
    public void record(Object domainEvent) {
      recorded.add(domainEvent);
    }
  }
}
