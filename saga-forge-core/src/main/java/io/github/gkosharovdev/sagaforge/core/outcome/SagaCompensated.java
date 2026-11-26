package io.github.gkosharovdev.sagaforge.core.outcome;

import java.time.Instant;
import java.util.Map;

/**
 * A saga instance ended {@code COMPENSATED}: every committed step it could undo was compensated
 * after {@code trigger} started compensation.
 *
 * <p>Every component is required: an {@link IllegalArgumentException} names the one that is missing
 * or blank.
 *
 * @param eventId {@code sagaId + ":COMPENSATED"}
 * @param sagaType the fully qualified name of the instance's {@code @Saga} class
 * @param sagaId the instance
 * @param associations the instance's associations
 * @param occurredAt when the instance ended
 * @param trigger what started the compensation
 */
public record SagaCompensated(
    String eventId,
    String sagaType,
    String sagaId,
    Map<String, String> associations,
    Instant occurredAt,
    OutcomeTrigger trigger)
    implements SagaOutcome {

  /** Validates every component and copies {@code associations}. */
  public SagaCompensated {
    Values.requireText(eventId, "eventId");
    Values.requireText(sagaType, "sagaType");
    Values.requireText(sagaId, "sagaId");
    associations = Values.associations(associations);
    Values.requireNonNull(occurredAt, "occurredAt");
    Values.requireNonNull(trigger, "trigger");
  }

  /** The outcome of {@code sagaId} with its {@link #eventId()} derived from the instance. */
  public static SagaCompensated of(
      String sagaType,
      String sagaId,
      Map<String, String> associations,
      Instant occurredAt,
      OutcomeTrigger trigger) {
    return of(sagaType, sagaId, associations, occurredAt, trigger, 0);
  }

  /**
   * The outcome of {@code sagaId} in compensation round {@code compensationRound}: {@link
   * #eventId()} is {@code sagaId:COMPENSATED}, with {@code :r<round>} appended from round 1 on.
   */
  public static SagaCompensated of(
      String sagaType,
      String sagaId,
      Map<String, String> associations,
      Instant occurredAt,
      OutcomeTrigger trigger,
      int compensationRound) {
    return new SagaCompensated(
        Values.eventId(sagaId, "COMPENSATED", compensationRound),
        sagaType,
        sagaId,
        associations,
        occurredAt,
        trigger);
  }
}
