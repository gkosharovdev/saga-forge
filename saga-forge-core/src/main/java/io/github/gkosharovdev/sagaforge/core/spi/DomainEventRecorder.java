package io.github.gkosharovdev.sagaforge.core.spi;

import io.github.gkosharovdev.sagaforge.core.spi.model.DomainTypes;

/**
 * Engine-facing SPI that receives the domain events recorded during a unit of work.
 *
 * <p>The engine creates a recorder per unit of work and binds it to the calling thread through
 * {@link DomainEventScope#runWith(DomainEventRecorder, Runnable)}. Client code never implements or
 * calls this interface directly; it records events through {@link
 * io.github.gkosharovdev.sagaforge.core.event.DomainEvents#record(Object)}, which validates the
 * event and then delegates to the bound recorder.
 *
 * <p>What the recorder does with an event (in-process delivery to the owning saga instance, or
 * buffering for delivery through the after-commit outbox ({@link Outbox}; by default {@link
 * EventDispatcher#dispatch(Object)})) is an engine concern; see {@code
 * contracts/event-publisher.md}.
 */
public interface DomainEventRecorder {

  /**
   * Records a domain event for the current unit of work.
   *
   * <p>Callers reaching this method through {@code DomainEvents.record} have already checked that
   * {@code domainEvent} is non-null and a domain event in this recorder's {@link #domainTypes()}.
   *
   * @param domainEvent the domain event to record; never {@code null} when called by the framework
   */
  void record(Object domainEvent);

  /**
   * The vocabularies whose domain events this recorder accepts; {@code DomainEvents.record} rejects
   * any other object before calling {@link #record} (specs/016-marker-spi-jmolecules).
   *
   * @return Saga Forge's annotations only, unless the recorder's runtime enables more
   */
  default DomainTypes domainTypes() {
    return DomainTypes.builtIn();
  }
}
