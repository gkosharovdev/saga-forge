package io.github.gkosharovdev.sagaforge.core.outcome;

import java.time.Instant;
import java.util.Map;

/**
 * The announcement that a saga instance ended: {@link SagaCompleted}, {@link SagaFailed} or {@link
 * SagaCompensated}.
 *
 * <p>The runtime hands one to the outbox in the same unit of work that stores the instance's
 * terminal status, as the last event of that batch. It is a framework event, not a
 * {@code @DomainEvent}: no saga step can take it, and an {@code EventReceiver} that receives one
 * reports that no saga reacted. It carries no failure message, context value or saga field.
 */
public sealed interface SagaOutcome permits SagaCompleted, SagaFailed, SagaCompensated {

  /**
   * The event's stable identity, {@code sagaId + ":" + terminal status} ({@code COMPLETED}, {@code
   * FAILED}, {@code COMPENSATION_FAILED} or {@code COMPENSATED}): the same on every delivery, so
   * listeners can deduplicate.
   */
  String eventId();

  /** The fully qualified name of the instance's {@code @Saga} class. */
  String sagaType();

  /** The instance that ended. */
  String sagaId();

  /** The instance's associations, association property to value; unmodifiable and never empty. */
  Map<String, String> associations();

  /**
   * When the instance ended: the terminal write's time, equal to the instance's {@code
   * lastModifiedAt}.
   */
  Instant occurredAt();
}
